package play.groovysupport

import groovy.io.FileType
import org.junit.Assert
import play.Logger
import play.Play
import play.PlayPlugin
import play.cache.Cache
import play.classloading.ApplicationClasses.ApplicationClass
import play.classloading.ApplicationClassloaderState
import play.classloading.BytecodeCache
import play.classloading.HotswapAgent
import play.exceptions.CompilationException
import play.groovysupport.compiler.CompilationErrorException
import play.groovysupport.compiler.CompilationResult
import play.groovysupport.compiler.GroovyCompiler
import play.test.BaseTest
import play.test.FunctionalTest
import play.test.GebTest
import play.test.TestEngine
import play.test.TestEngine.TestResults
import play.vfs.VirtualFile
import spock.lang.Specification

import java.lang.reflect.Modifier
import java.security.ProtectionDomain
import play.groovysupport.compiler.ClassDefinition

class GroovyPlugin extends PlayPlugin {

    def compiler
    def classloader

    @Override
    void onLoad() {

        def stubsFolder = new File(Play.tmpDir, 'groovy_stubs');
        compiler = new GroovyCompiler(Play.applicationPath,
                new File(Play.modules['groovy'].getRealFile(), 'lib'),
                System.getProperty('java.class.path')
                        .split(System.getProperty('path.separator')) as List,
                Play.tmpDir,
                stubsFolder
        )
        classloader = new PlayGroovyClassLoader()

        onConfigurationRead()

        /**
         * The Play TestEngine only grabs classes which are assignable from
         * org.junit.Assert -- Spock tests don't extend from JUnit, so we need
         * to modify the TestEngine.allUnitTests method to ensure it picks up
         * Specification classes too
         */
        TestEngine.metaClass.static.allUnitTests = {
            Play.classloader.getAssignableClasses(Assert.class)
                    .plus(Play.classloader.getAssignableClasses(Specification.class))
                    .findAll {
                !Modifier.isAbstract(it.getModifiers()) &&
                        !FunctionalTest.class.isAssignableFrom(it) &&
                        !GebTest.class.isAssignableFrom(it)
            }
        }

        TestEngine.metaClass.static.allGebTests << {
            Play.classloader.getAssignableClasses(GebTest.class)
                    .findAll { !Modifier.isAbstract(it.getModifiers()) }
        }

        Logger.info('Groovy support is active')
    }

    @Override
    TestResults runTest(Class<BaseTest> testClass) {

        null
    }

    def isChanged = {file ->
        for (appClass in Play.@classes.all()) {
            if (appClass.javaFile.realFile == file) {
                return (appClass.timestamp < file.lastModified())
            }
        }

        return true;
    }

    @Override
    boolean detectClassesChange() {
        Logger.debug("Updating changed classes")
        try {
            def sources = findSources(isChanged)
            Logger.debug("Updated sources: ${sources}")
            if (sources.groovy) {
                def groovy = updateGroovy(sources.groovy)
                updateInternalApplicationClasses(groovy, true)
            }
            if (sources.java) {
                def java = updateJava(sources.java)
                updateInternalApplicationClasses(java, true)
            }

            if (sources.java || sources.groovy) {
                removeDeletedClasses();
            }
        } catch (CompilationErrorException e) {
            throw compilationException(e.compilationError)
        }

        return true;
    }

    class PlayGroovyClassLoader extends ClassLoader {
        PlayGroovyClassLoader() {
            super(Play.classloader)
        }

        def getClass(name, code) {
            try {
                return loadClass(name)
            } catch (ClassNotFoundException ex) {
                return super.defineClass(name, code)
            }
        }
    }
//    def defineClass(name, code) {
//        def method = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class)
//        method.accessible = true
//        Class clazz = method.invoke(Play.classloader, name, code, 0, code.length, Play.classloader.protectionDomain)
//        return clazz;
//    }

    @Override
    boolean compileSources() {
        Logger.debug("Recompiling all sources")
        try {
            def sources = findSources()
            def groovy = updateGroovy(sources.groovy)
            updateInternalApplicationClasses(groovy, false)
            def java = updateJava(sources.java)
            updateInternalApplicationClasses(java, false)
        } catch (CompilationErrorException e) {
            throw compilationException(e.compilationError)
        }

        return true
    }

    /**
     * Update Play internal ApplicationClasses
     */
    def updateInternalApplicationClasses(result, update = false) {
        Logger.debug("Updating internal Play classes: ${result.updatedClasses*.name}")

        boolean sigChanged = false
        def toReload = []
        result.updatedClasses.each {
            //We replace byte code in current application class
            //as it was already created by stub generator and compiled
            //with Java compiler
            def appClass = Play.@classes.getApplicationClass(it.name)
            if (!appClass) appClass = new ApplicationClass(it.name)
            appClass.javaFile = VirtualFile.open(it.source)
            appClass.javaByteCode = it.code
            appClass.enhancedByteCode = it.code
            appClass.compiled = true;
            appClass.javaSource = it.source.text
            //We can safely use modification stamp of a stub, it will be more recent then groovy file timestamp
            appClass.timestamp = it.source.lastModified()
            //Groovy classes also need Play byte code enhances
            def oldSum = appClass.sigChecksum
            appClass.enhance()
            sigChanged = oldSum != appClass.sigChecksum
            //Make Play see (replace) current classes
            Play.@classes.add(appClass)
            //Need to cache byte code or you won't see any changes
            BytecodeCache.cacheBytecode(appClass.enhancedByteCode, appClass.name, it.source.text)
            if (!appClass.javaClass) {
                appClass.javaClass = classloader.getClass(appClass.name, appClass.enhancedByteCode)
            }
            toReload << new java.lang.instrument.ClassDefinition(appClass.javaClass, appClass.enhancedByteCode)
        }

        if (update && sigChanged) {
            println "SIG CHANGE"
            throw new RuntimeException("Signature change !");
        }

        if (update && toReload) {
            Cache.clear();
            if (HotswapAgent.enabled) {
                try {
                    HotswapAgent.reload(toReload as java.lang.instrument.ClassDefinition[])
                } catch (Throwable e) {
                    throw new RuntimeException("Need reload")
                }
            } else {
                throw new RuntimeException("Need reload")
            }
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
        Play.javaPath.grep({it.exists()}).each {virtualFile ->
            virtualFile.realFile.eachFileRecurse(FileType.FILES, { f ->
                if (!filter || filter(f)) {
                    if (f.name.endsWith('.java')) {
                        java << f
                    } else if (f.name.endsWith('.groovy')) {
                        groovy << f;
                    }
                }
            })
        }

        return ['java': java, 'groovy': groovy]
    }

    def toClassName(file, classRootFolder) {
        def relativePath = file.absolutePath[classRootFolder.absolutePath.length() + 1..file.absolutePath.lastIndexOf('.') - 1]
        def className = relativePath.replaceAll(/(\/|\\)/, '.')
        return className;
    }

    def updateGroovy(sources) {
        Logger.debug("Compiling groovy classes: ${sources*.name}")
        // sources have changed, so compile them
        def result = compiler.update(sources)
        return result
    }

    def updateJava(sources) {
        Logger.debug("Compiling java classes: ${sources*.name}")
        def compiled = []
        def modified = Play.@classes.all().grep({sources.contains(it.javaFile.realFile)})
        modified.each {
            it.refresh()
            if (it.compile()) {
                compiled << new ClassDefinition(name: it.name, code: it.javaByteCode, source: it.javaFile.realFile)
            } else {
                Play.@classes.remove(it)
            }
        }
        return new CompilationResult(compiled, Collections.emptyList())
    }

}
