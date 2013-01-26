package play.groovysupport

import groovy.io.FileType
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
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

import java.lang.reflect.Method
import java.security.ProtectionDomain

import play.groovysupport.compiler.*

@TypeChecked(extensions = [])
class GroovyPlugin extends PlayPlugin {

    GroovyCompiler groovyCompiler = new GroovyCompiler(new PlayGroovyCompilerConfiguration())
    ClearGroovyStampsEnhancer clearStampsEnhancer = new ClearGroovyStampsEnhancer()
    EnsureStaticClassInfoEnhancer ensureStaticClassInfoEnhancer = new EnsureStaticClassInfoEnhancer()
    TestRunnerEnhancer testRunnerEnhancer = new TestRunnerEnhancer()

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
    void onConfigurationRead() {
        groovyCompiler = new GroovyCompiler(new PlayGroovyCompilerConfiguration())
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
        List<Source> sources = findSources(this.&isChanged)
        Logger.debug("Updated sources: ${sources}")
        if (sources) {
            //Groovy groovyCompiler needs to have also java files to support cross compilation
            //it will not compile them but needs to resolve classes there to compile Groovy code
            Collection<ClassDefinition> classes = groovyCompiler.update(sources)

            updateApplicationClasses(classes)
            enhanceApplicationClasses(classes)

            Collection<ClassDefinition> toHotswap = classes.grep { ClassDefinition it -> !it.newClass }
            hotswapClasses(toHotswap)

            removeDeletedClasses()

            cacheByteCode(classes)
        }

        Logger.debug("Changed classes updated")
        return true;
    }

    @Override
    boolean compileSources() {
        Logger.debug "START FULL COMPILATION"
        long start = System.currentTimeMillis()
        List<Source> sources = findSources()

        Collection<ClassDefinition> classes = groovyCompiler.update(sources)
        updateApplicationClasses(classes)

        Collection toEnhance = new ArrayList(classes.size())
        //Try to get enhanced code from cache
        classes.each { ClassDefinition it ->
            ApplicationClass appClass = it.appClass
            byte[] cachedBytecode = BytecodeCache.getBytecode(appClass.name, appClass.javaSource)

            if (cachedBytecode) {
                appClass.enhancedByteCode = cachedBytecode
            } else {
                toEnhance << it
            }
        }

        enhanceApplicationClasses(toEnhance)
        cacheByteCode(toEnhance)

        Logger.debug "FULL COMPILATION TOOK: ${(System.currentTimeMillis() - start) / 1000}"
        return true
    }

    @Override
    void enhance(ApplicationClass applicationClass) {
        clearStampsEnhancer.enhanceThisClass(applicationClass)
        ensureStaticClassInfoEnhancer.enhanceThisClass(applicationClass)
        testRunnerEnhancer.enhanceThisClass(applicationClass)
    }

    private boolean isChanged(File file) {
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
    List<Source> findSources(Closure filter = null) {
        List<Source> sources = new ArrayList<>()
        Play.javaPath.grep({ VirtualFile it -> it.exists() }).each { VirtualFile virtualFile ->
            virtualFile.realFile.eachFileRecurse(FileType.FILES, { File f ->
                if (!filter || filter(f)) {
                    if (f.name.endsWith('.java')) {
                        sources << new Source(virtualFile.realFile, f)
                    } else if (f.name.endsWith('.groovy')) {
                        sources << new Source(virtualFile.realFile, f)
                    }
                }
            })
        }

        return sources
    }

    /**
     * Update Play internal ApplicationClasses
     */
    private void updateApplicationClasses(Collection<ClassDefinition> updatedClasses) {
        Logger.debug("Updating internal Play classes: ${updatedClasses.join("\n")}")

        updatedClasses.each { ClassDefinition it ->
            ApplicationClass appClass = toApplicationClass(it)
            it.appClass = appClass
            Play.@classes.add(appClass)
        }
    }

    private void enhanceApplicationClasses(Collection<ClassDefinition> toEnhance) {
        toEnhance.each { ClassDefinition classDef ->
            ApplicationClass appClass = classDef.appClass
            appClass.enhancedByteCode = appClass.enhance()

            if (!appClass.javaClass) {
                appClass.javaClass = getClass(appClass.name, appClass.enhancedByteCode)
            }
        }
    }

    void hotswapClasses(Collection<ClassDefinition> classes) {

        List toReload = new ArrayList(classes.size())

        classes.each { ClassDefinition classDef ->
            if (classDef.groovy) {
                //Groovy classes need method call cache cleared on hotswap
                try {
                    CallSiteRemover.clearCallSite(classDef.appClass.javaClass)
                } catch (Exception ex) {
                    throw new RuntimeException("Could not clear CallSite. Need reload!")
                }
            }

            toReload << new java.lang.instrument.ClassDefinition(classDef.appClass.javaClass, classDef.appClass.enhancedByteCode)
        }

        if (toReload) {
            if (HotswapAgent.enabled) {
                Cache.clear();
                try {
                    HotswapAgent.reload(toReload as java.lang.instrument.ClassDefinition[])
                } catch (Throwable e) {
                    Logger.info("Error hotswapping classes: ${e.localizedMessage}. Need to reload whole application")
                    throw new RuntimeException("Need reload")
                }
            } else {
                Logger.info("Hot swap disabled. Need to reload whole application")
                throw new RuntimeException("Need reload")

            }
        }
    }

    private void cacheByteCode(Collection<ClassDefinition> classDefs) {
        classDefs.each { ClassDefinition it ->
            ApplicationClass appClass = it.appClass
            BytecodeCache.cacheBytecode(appClass.enhancedByteCode, appClass.name, appClass.javaSource)
        }
    }

    private void removeDeletedClasses() {
        Logger.debug("Removing deleted classes from classloader")
        Play.@classes.all().each { ApplicationClass it ->
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

    Class getClass(String name, byte[] code) {
        try {
            Method method = ApplicationClassloader.class.getMethod('loadClass', String.class)
            method.accessible = true
            return method.invoke(Play.classloader, name) as Class
        } catch (Exception ex) {
            Method method = ClassLoader.class.getDeclaredMethod('defineClass', String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class)
            method.accessible = true
            return method.invoke(Play.classloader, name, code, 0, code.length, Play.classloader.protectionDomain) as Class
        }
    }

    private ApplicationClass toApplicationClass(ClassDefinition classDef) {
        ApplicationClass appClass = Play.@classes.getApplicationClass(classDef.name)

        if (!appClass) {
            appClass = new ApplicationClass()
            appClass.name = classDef.name
        }

        appClass.javaFile = VirtualFile.open(classDef.source)
        appClass.javaByteCode = classDef.code
        appClass.enhancedByteCode = classDef.code
        appClass.compiled = true;
        appClass.javaSource = classDef.source.text
        appClass.timestamp = classDef.source.lastModified()

        return appClass
    }
}
