package play.groovysupport

import groovy.io.FileType
import play.Logger
import play.Play
import play.PlayPlugin
import play.cache.Cache
import play.classloading.ApplicationClasses.ApplicationClass
import play.classloading.ApplicationClassloader
import play.classloading.ApplicationClassloaderState
import play.classloading.BytecodeCache
import play.classloading.HotswapAgent
import play.exceptions.CompilationException
import play.groovysupport.compiler.CallSiteRemover
import play.groovysupport.compiler.ClassDefinition
import play.groovysupport.compiler.CompilationErrorException
import play.groovysupport.compiler.GroovyCompiler
import play.test.BaseTest
import play.test.TestEngine.TestResults
import play.vfs.VirtualFile

import java.security.ProtectionDomain

class GroovyPlugin extends PlayPlugin {

    def compiler
    def clearStampsEnhancer = new ClearGroovyStampsEnhancer()
    def testRunnerEnhancer = new TestRunnerEnhancer()

    @Override
    void onLoad() {

        def stubsFolder = new File(Play.tmpDir, 'groovy_stubs');
        compiler = new GroovyCompiler(System.getProperty('java.class.path')
                .split(System.getProperty('path.separator')) as List,
                Play.tmpDir,
                stubsFolder
        )

        Logger.info('Groovy support is active')
    }

    def isChanged = { file ->
        for (appClass in Play.@classes.all()) {
            if (appClass.javaFile.realFile == file) {
                return (appClass.timestamp < file.lastModified())
            }
        }

        return true;
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
        try {
            def sources = findSources(isChanged)
            Logger.debug("Updated sources: ${sources}")
            if (sources.groovy) {
                def groovy = updateGroovy(sources.groovy)
                def toReload = updateInternalApplicationClasses(groovy)
                hotswapClasses(toReload)
            }
            if (sources.java) {
                def java = updateJava(sources.java)
                def toReload = updateInternalApplicationClasses(java)
                hotswapClasses(toReload)
            }

            if (sources.java || sources.groovy) {
                removeDeletedClasses();
            }
        } catch (CompilationErrorException e) {
            throw compilationException(e.compilationError)
        }

        return true;
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

    @Override
    boolean compileSources() {
        Logger.debug("Recompiling all sources")
        Logger.debug "START FULL COMPILATION"
        def start = System.currentTimeMillis()
        try {
            def sources = findSources()

            def groovy = updateGroovy(sources.groovy)
            updateInternalApplicationClasses(groovy)
            def java = updateJava(sources.java)
            updateInternalApplicationClasses(java)
        } catch (CompilationErrorException e) {
            throw compilationException(e.compilationError)
        }

        Logger.debug "FULL COMPILATION TOOK: ${(System.currentTimeMillis() - start) / 1000}"
        return true
    }

    /**
     * Update Play internal ApplicationClasses
     */
    def updateInternalApplicationClasses(updatedClasses) {
        Logger.debug("Updating internal Play classes: ${updatedClasses*.name}")

        def toReload = []
        updatedClasses.each {
            boolean sigChanged
            def appClass = Play.@classes.getApplicationClass(it.name)
            if (!appClass) appClass = new ApplicationClass(it.name)
            appClass.javaFile = VirtualFile.open(it.source)
            appClass.javaByteCode = it.code
            def cachedBytecode = BytecodeCache.getBytecode(it.name, it.source.text)

            def newClass = !Play.@classes.hasClass(appClass.name)

            if (cachedBytecode) {
                appClass.enhancedByteCode = cachedBytecode
            } else {
                appClass.enhancedByteCode = it.code

                //Groovy classes also need Play byte code enhances
                def oldSum = appClass.sigChecksum

                appClass.enhance()
                sigChanged = oldSum != appClass.sigChecksum

                BytecodeCache.cacheBytecode(appClass.enhancedByteCode, appClass.name, it.source.text)
            }

            appClass.compiled = true;
            appClass.javaSource = it.source.text
            appClass.timestamp = it.source.lastModified()
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

    def compilationException(compilationError) {
        if (compilationError.source) {
            new CompilationException(
                    VirtualFile.open(compilationError.source), compilationError.message,
                    compilationError.line, compilationError.start, compilationError.end
            )
        } else {
            new CompilationException(compilationError.message)
        }
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
                        java << [baseFolder: virtualFile.realFile, file: f]
                    } else if (f.name.endsWith('.groovy')) {
                        groovy << [baseFolder: virtualFile.realFile, file: f];
                    }
                }
            })
        }

        return ['java': java, 'groovy': groovy]
    }

    def updateGroovy(sources) {
        Logger.debug("Compiling groovy classes: ${sources*.file.name}")
        // sources have changed, so compile them
        def result = compiler.update(sources*.file)
        return result
    }

    def updateJava(sources) {
        Logger.debug("Compiling java classes: ${sources*.file.name}")
        def compiled = []
        def sourceFiles = sources*.file
        def modified = new HashSet()
        modified.addAll(Play.@classes.all().grep { sourceFiles.contains(it.javaFile.realFile) })
        def loadedClasses = Play.@classes.all()*.javaFile.realFile
        def newFiles = sources.grep { !loadedClasses.contains(it.file) }
        newFiles.each {
            if (!loadedClasses.contains(it.file)) {
                def appClass = Play.@classes.getApplicationClass(toClassName(it.baseFolder, it.file))
                modified << appClass
            }
        }

        modified.each {
            it.refresh()
            if (it.compile()) {
                compiled << new ClassDefinition(name: it.name, code: it.javaByteCode, source: it.javaFile.realFile)
            } else {
                Play.@classes.remove(it)
            }
        }
        return compiled
    }

    @Override
    void enhance(ApplicationClass applicationClass) {
        clearStampsEnhancer.enhanceThisClass(applicationClass)
        testRunnerEnhancer.enhanceThisClass(applicationClass)
    }

    def toClassName(baseFolder, file) {
        def path = file.absolutePath
        def name = path.substring(baseFolder.absolutePath.length() + 1, path.lastIndexOf('.'))
        name = name.replaceAll('[/\\\\]', '.')
        return name
    }

    public Collection<Class> getUnitTests() {
        return TestRunnerEnhancer.spockTests
    }

    public Collection<Class> getFunctionalTests() {
        return TestRunnerEnhancer.gebTests
    }
}
