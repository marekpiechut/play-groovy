package play.groovysupport

import java.lang.reflect.Modifier

import play.*
import play.test.*
import play.test.TestEngine.TestResults
import play.exceptions.*
import play.vfs.VirtualFile
import play.classloading.ApplicationClasses.ApplicationClass
import play.groovysupport.compiler.*

import org.junit.Assert

import spock.lang.Specification
import org.codehaus.groovy.tools.javac.JavaStubCompilationUnit
import play.classloading.BytecodeCache
import groovy.io.FileType
import play.classloading.ApplicationClassloader
import play.cache.Cache
import play.classloading.ApplicationClassloaderState
import play.classloading.ApplicationCompiler
import java.lang.instrument.ClassDefinition
import play.classloading.HotswapAgent
import play.groovysupport.compiler.ClassDefinition

class GroovyPlugin extends PlayPlugin {

    def compiler
    def currentSources

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

    @Override
    boolean detectClassesChange() {

        def sources = findSources()
        try {
            def groovy = updateGroovy(sources.groovy.grep(isChanged))
            updateInternalApplicationClasses(groovy)
            def java = updateJava(sources.java.grep(isChanged))
            updateInternalApplicationClasses(java)

            if (java || groovy) {
//                removeDeletedClasses();
            }
        } catch (CompilationErrorException e) {
            throw compilationException(e.compilationError)
        }

        return true;
    }

    @Override
    boolean compileSources() {

        try {
            def sources = findSources()
            updateGroovy(sources.groovy)
            updateJava()
        } catch (CompilationErrorException e) {
            throw compilationException(e.compilationError)
        }

        return true
    }

    def isChanged = {file ->
        for (appClass in Play.@classes.all()) {
            if (appClass.javaFile.realFile.absolutePath == entry.file.absolutePath) {
                return (appClass.timestamp < file.lastModified())
            }
        }

        return true;
    }

    /**
     * Update Play internal ApplicationClasses
     */
    def updateInternalApplicationClasses(CompilationResult result) {

        def sigChanged = false
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
//            toReload << new ClassDefinition(appClass.javaClass, appClass.enhancedByteCode)
        }

        if (sigChanged) {
            throw new RuntimeException("Signature change !");
        }

        if (toReload) {
            Cache.clear();
            if (HotswapAgent.enabled) {
                try {
                    HotswapAgent.reload(toReload as ClassDefinition[])
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
            }
            if (applicationClass.name.contains('$')) {
                Play.classes.classes.remove(applicationClass.name);
                currentState = new ApplicationClassloaderState();//show others that we have changed..
                // Ok we have to remove all classes from the same file ...
                VirtualFile vf = applicationClass.javaFile;
                for (ApplicationClass ac : Play.classes.all()) {
                    if (ac.javaFile.equals(vf)) {
                        Play.classes.classes.remove(ac.name);
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
    def findSources() {
        def java = []
        def groovy = []
        Play.javaPath.each {virtualFile ->
            virtualFile.realFile.eachFileRecurse(FileType.FILES, { f ->
                if (f.name.endsWith('.java')) {
                    java << f
                } else if (f.name.endsWith('.groovy')){
                    groovy << f;
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

        if (currentSources != sources) {
            // sources have changed, so compile them
            Logger.debug('Compiling sources')

            def result = compiler.update(sources)
            currentSources = sources

            return result
        }

        return null
    }

    def updateJava() {
        def compiled = []
        def modified = Play.@classes.all().grep({it.timestamp < it.javaFile.lastModified()})
        modified.addAll(Play.pluginCollection.onClassesChange(modified))
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
