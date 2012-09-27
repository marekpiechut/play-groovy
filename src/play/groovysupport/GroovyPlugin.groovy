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

        Play.javaPath.add(0, VirtualFile.fromRelativePath('tmp/groovy_stubs'))

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

		def sources = sources("groovy");

        def update = { src ->
            for (entry in src) {
                def appClass = Play.classes.getApplicationClass(entry.className)
                if (!appClass || appClass.timestamp < entry.modifyStamp) {
                    return true
                }

                return false
            }
        }

        if (update(sources)) {
            generateStubs(sources)
            Play.classloader.detectChanges()
            def result = updateGroovy(sources)
            if (result) {
                updateInternalApplicationClasses(result)
            }
            return true;
        }

        return false
	}

	@Override
	boolean compileSources() {

		try {
			def groovySources = sources("groovy")
            generateStubs(groovySources)

            def javaSources = sources("java")

            Play.@classes.compiler.compile(javaSources*.className as String[])

            def result = updateGroovy(groovySources)

			if (result) {
				updateInternalApplicationClasses(result)
			}
		} catch (CompilationErrorException e) {
			throw compilationException(e.compilationError)
		}

		return true
	}

	/**
	 * Update Play internal ApplicationClasses
	 */
	def updateInternalApplicationClasses(CompilationResult result) {

		// remove deleted classes
		result.removedClasses.each {
			Play.classes.remove(it.name)
		}

		// add/update other classes
		result.updatedClasses.each {
            //We replace byte code in current application class
            //as it was already created by stub generator and compiled
            //with Java compiler
            def appClass = Play.classes.getApplicationClass(it.name)
			appClass.compiled(it.code)
            //We can safely use modification stamp of a stub, it will be more recent then groovy file timestamp
            appClass.timestamp = appClass.javaFile.lastModified()
            //Groovy classes also need Play byte code enhances
            appClass.enhance()
            //Make Play see (replace) current classes
			Play.@classes.add(appClass)
            //Need to cache byte code or you won't see any changes
            BytecodeCache.cacheBytecode(appClass.enhancedByteCode, appClass.name, it.source.text)
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
     * Get groovy sources from current Play javaPath
     *
     * @return Map [file -> modify stamp]
     */
    def sources(extension) {
        def sources = []
        Play.javaPath.each {
            sources += compiler.getSourceFiles(it.getRealFile(), extension)
        }

        return sources
	}

    def generateStubs(groovySources) {
        def classLoader = new GroovyClassLoader(Play.classloader.rootLoader, compiler.compilerConf)
        def compilationUnit = new JavaStubCompilationUnit(compiler.compilerConf, classLoader, compiler.stubsFolder)
        groovySources.each {
            compilationUnit.addSource(it.file)
        }

        compilationUnit.compile()
    }

	def updateGroovy(sources) {

		if (currentSources != sources) {
			// sources have changed, so compile them
			Logger.debug('Compiling Groovy sources')

			def result = compiler.update(sources)
			currentSources = sources

			return result
		}

		return null
	}

}
