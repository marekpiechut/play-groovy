package play.groovysupport

import groovy.io.FileType
import play.Logger
import play.Play
import play.Play.Mode
import play.PlayPlugin
import play.cache.Cache
import play.classloading.ApplicationClasses.ApplicationClass
import play.classloading.ApplicationClassloader
import play.classloading.ApplicationClassloaderState
import play.classloading.BytecodeCache
import play.classloading.HotswapAgent
import play.vfs.VirtualFile

import java.security.ProtectionDomain

import play.groovysupport.compiler.*

class GroovyPlugin extends PlayPlugin {

    def groovyCompiler = new GroovyCompiler(new PlayGroovyCompilerConfiguration())
    def javaCompiler = new JavaCompiler()
    def clearStampsEnhancer = new ClearGroovyStampsEnhancer()
    def testRunnerEnhancer = new TestRunnerEnhancer()

    @Override
    void onLoad() {
        Logger.info('Groovy support is active')
    }

    @Override
    void onApplicationReady() {
        if (Play.mode == Mode.DEV) {
            boolean compile = Play.configuration.getProperty("play.groovy.compileOnInit", "false").toBoolean()
            if (compile) {
                //Need to start application to ensure all classes are compiled
                //before first request is done and tries to use stock Play
                //groovyCompiler instead of plugins (bug in Play) that won't find groovy classes
                Logger.info("Starting application (set play.groovy.compileOnInit=false in application.conf to disable)")
                Play.start()
            }
        }
    }

    @Override
    boolean detectClassesChange() {
        if (!Play.started) {
            //Don't try to compile all stuff as it will be compiled again in a second
            //when Play is starting
            Logger.debug("Play not started yet. Ignoring detect changes request.")
            return true;
        }

        Logger.debug("Updating changed classes")
        def sources = findSources(isChanged)
        Logger.debug("Updated sources: ${sources}")
        if (sources.groovy) {
            //Groovy groovyCompiler needs to have also java files to support cross compilation
            //it will not compile them but needs to resolve classes there to compile Groovy code
            def allSources = sources.java + sources.groovy
            def groovy = groovyCompiler.update(allSources)
            def toReload = updateInternalApplicationClasses(groovy)
            hotswapClasses(toReload)
        }
//        if (sources.java) {
//            def java = javaCompiler.update(sources.java)
//            def toReload = updateInternalApplicationClasses(java)
//            hotswapClasses(toReload)
//        }

        if (sources.java || sources.groovy) {
            removeDeletedClasses();
        }

        return true;
    }

    @Override
    boolean compileSources() {
        Logger.debug("Recompiling all sources")
        Logger.debug "START FULL COMPILATION"
        def start = System.currentTimeMillis()
        def sources = findSources()

        //Groovy groovyCompiler needs to have also java files to support cross compilation
        //it will not compile them but needs to resolve classes there to compile Groovy code
        def allSources = sources.java + sources.groovy

        def groovy = groovyCompiler.update(allSources)
//        def java = javaCompiler.update(sources.java)

        updateInternalApplicationClasses(groovy)

        Logger.debug "FULL COMPILATION TOOK: ${(System.currentTimeMillis() - start) / 1000}"
        return true
    }

    @Override
    void enhance(ApplicationClass applicationClass) {
        clearStampsEnhancer.enhanceThisClass(applicationClass)
        testRunnerEnhancer.enhanceThisClass(applicationClass)
    }

    def isChanged = { file ->
        for (appClass in Play.@classes.all()) {
            if (appClass.javaFile.realFile == file) {
                return (appClass.timestamp < file.lastModified())
            }
        }

        return true;
    }

    /**
     * Get groovy and java sources that were modified from current Play javaPath
     *
     * @return Map [file -> modify stamp]
     */
    def findSources(filter = null) {
        def java = []
        def groovy = []
        Play.javaPath.grep({ it.exists() }).each { virtualFile ->
            virtualFile.realFile.eachFileRecurse(FileType.FILES, { f ->
                if (!filter || filter(f)) {
                    if (f.name.endsWith('.java')) {
                        java << new Source(virtualFile.realFile, f)
                    } else if (f.name.endsWith('.groovy')) {
                        groovy << new Source(virtualFile.realFile, f)
                    }
                }
            })
        }

        return ['java': java, 'groovy': groovy]
    }

    def hotswapClasses(toReload) {
        if (HotswapAgent.enabled && toReload) {
            Cache.clear();
            try {
                HotswapAgent.reload(toReload as java.lang.instrument.ClassDefinition[])
            } catch (Throwable e) {
                throw new RuntimeException("Need reload")
            }
        } else {
            throw new RuntimeException("Need reload")
        }
    }

    def removeDeletedClasses() {
        Logger.debug("Removing deleted classes from classloader")
        Play.@classes.all().each {
            if (!it.javaFile.exists()) {
                Play.@classes.remove(it)
                Logger.debug("Removed: ${it.name}")

                if (it.name.contains('$')) {
                    Play.@classes.remove(it.name);
                    Play.classloader.currentState = new ApplicationClassloaderState();//show others that we have changed..
                    // Ok we have to remove all classes from the same file ...
                    VirtualFile vf = it.javaFile;
                    for (ApplicationClass ac : Play.@classes.all()) {
                        if (ac.javaFile.equals(vf)) {
                            Play.@classes.remove(ac.name);
                            Logger.debug("Removed: ${ac.name}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Update Play internal ApplicationClasses
     */
    def updateInternalApplicationClasses(updatedClasses) {
        Logger.debug("Updating internal Play classes: ${updatedClasses*.name}")


        updatedClasses.each {
            def appClass = Play.@classes.getApplicationClass(it.name)
            if (!appClass) appClass = new ApplicationClass(it.name)
            appClass.javaFile = VirtualFile.open(it.source)
            appClass.javaByteCode = it.code
            appClass.compiled = true;
            appClass.javaSource = it.source.text
            appClass.timestamp = it.source.lastModified()

            def cachedBytecode = BytecodeCache.getBytecode(it.name, it.source.text)
            if (cachedBytecode) {
                appClass.enhancedByteCode = cachedBytecode
            } else {
                appClass.enhancedByteCode = it.code
            }

            Play.@classes.add(appClass)
        }

        def toReload = []
        updatedClasses.each {
            def appClass = Play.@classes.getApplicationClass(it.name)
            def cachedBytecode = BytecodeCache.getBytecode(it.name, it.source.text)

            def newClass = !Play.@classes.hasClass(appClass.name)

            if (cachedBytecode) {
                appClass.enhancedByteCode = cachedBytecode
            } else {
                //Groovy classes also need Play byte code enhances
                appClass.enhance()

                BytecodeCache.cacheBytecode(appClass.enhancedByteCode, appClass.name, it.source.text)
            }

            //Make Play see (replace) current classes
            Play.@classes.add(appClass)
            //Need to cache byte code or you won't see any changes
            if (!appClass.javaClass) {
                appClass.javaClass = getClass(appClass.name, appClass.enhancedByteCode)
            }

            if (!newClass && it.source.name.endsWith('.groovy')) {
                //Groovy classes need method call cache cleared on hotswap
                try {
                    CallSiteRemover.clearCallSite(appClass.javaClass)
                } catch (Exception ex) {
                    throw new RuntimeException("Could not clear CallSite. Need reload!")
                }
            }

            if (!newClass) {
                toReload << new java.lang.instrument.ClassDefinition(appClass.javaClass, appClass.enhancedByteCode)
            }
        }

        return toReload
    }

    def enhanceUpdated(updatedClasses) {

    }

    def getClass(name, code) {
        try {
            def method = ApplicationClassloader.class.getMethod('loadClass', String.class)
            method.accessible = true
            return method.invoke(Play.classloader, name)
        } catch (Exception ex) {
            def method = ClassLoader.class.getDeclaredMethod('defineClass', String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class)
            method.accessible = true
            return method.invoke(Play.classloader, name, code, 0, code.length, Play.classloader.protectionDomain)
        }
    }
}
