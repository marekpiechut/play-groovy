package play.groovysupport.compiler

import org.codehaus.groovy.control.CompilerConfiguration

import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

import static org.codehaus.groovy.control.CompilationUnit.SourceUnitOperation
import org.codehaus.groovy.ast.ClassHelper

import groovy.transform.Immutable

import play.Play

import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit
import play.Logger

class GroovyCompiler {
	
	def app
	def output
	def compilerConf
	def prevClasses = [:]
	def classesToSources = [:]
    def stubsFolder

	def GroovyCompiler(File app, File libs, List classpath, File output, File stubsFolder) {
		
		this.app = app
		this.output = output
        this.stubsFolder = stubsFolder
		// TODO: set source encoding to utf8
		compilerConf = new CompilerConfiguration()
		compilerConf.setTargetDirectory(new File(output, 'classes/'))
		compilerConf.setClasspathList(classpath)
        def sourceVersion = Play.configuration.get('java.source', '1.5')
        Logger.debug("Compiling using ${sourceVersion} source/target level")
        def compilerOptions = ['source': sourceVersion, 'target': sourceVersion, 'keepStubs' : true, 'stubDir' : stubsFolder]
        compilerConf.setDebug(true)
        compilerConf.setRecompileGroovySource(true)
        compilerConf.setJointCompilationOptions(compilerOptions)
	}

	File classNameToFile(className) {
		def classFile = new File(output, 'classes/' + className.replace('.', '/') + '.class')
		return classFile.exists() ? classFile : null
		// TODO: instead of null, throw an exception? What to do if the class
		// source can't be found?
	}

	def classNameToSource(name) {
		if (name.contains('$')) {
			// inner classes will be in the same file as
			// their parent class, so look on that instead
			name = name.substring(0, name.indexOf('$'))
		}
		return classesToSources[name]
	}

    static def fileToClassName(file) {
        def src = file.absolutePath
        // remove file extension
        src = src.substring(0, src.lastIndexOf('.'))

        //We have to remove classpath prefix from file name
        //to make sure we can get fully qualified class name from it
        for (jPath in Play.javaPath) {
            def path = jPath.realFile.absolutePath;
            if (src.startsWith(path)) {
                src = src.substring(path.length() + 1)
                break
            }
        }

        def className = src.replace(File.separator, '.')
        return className
    }

	CompilationResult update(List sources) {
		
		// TODO: investigate if there's a better way than creating new
		// CompilationUnit instances every time...
		def cu = new JavaAwareCompilationUnit(compilerConf, new GroovyClassLoader())

		// reset classesToSources map
		classesToSources = [:]

		// fix static star imports, see comment on field
		cu.addPhaseOperation(importFixer, org.codehaus.groovy.control.Phases.CONVERSION)
		cu.addSources(sources as File[])

		try {

			def newClasses = [:]

			cu.compile()

			cu.getClasses().each { 
				newClasses[it.getName()] = [bytes: it.getBytes()]
			}

			// now that compilation is done we can create the classesToSources map
			for (sourceUnit in cu) {
				sourceUnit.getAST().classes.each { clazz ->
					// ignore inner classes
					if (!clazz.name.contains('$')) {
						classesToSources[clazz.name] = new File(sourceUnit.name)
					}
				}
			}
			
			// NOTE: since the CompilationUnit will simply recompile everything
			// it's given, we're not bothering with 'recompiled' classes

			def updated = newClasses.keySet()
				.collect { cn ->
					new ClassDefinition(name: cn, 
						code: newClasses[cn].bytes, source: classNameToSource(cn)) 
				}

            //TODO: Removed classes will not work this way if we only recompile changed classes
			def removed = prevClasses.keySet().findAll { !(it in newClasses.keySet()) }
				.collect { cn -> 
					new ClassDefinition(name: cn, code: null, source: null) 
				}

			prevClasses = newClasses
			
			return new CompilationResult(updated, removed)

		} catch (MultipleCompilationErrorsException e) {
			
			if (e.getErrorCollector().getLastError() != null) {

				def errorMessage = e.getErrorCollector().getLastError()// as SyntaxErrorMessage
				if (errorMessage instanceof SimpleMessage) {
					// TODO: this shouldn't happen but handle it somehow
					// just in case
					println 'This is really bad and should not have happened'
					e.printStackTrace()
					System.exit(1)
				}
				else {
					errorMessage = errorMessage as SyntaxErrorMessage
					def syntaxException = errorMessage.getCause()
				
					def compilationError = new CompilationError(
						message: syntaxException.getMessage(),
						line: syntaxException.getLine(),
						start: syntaxException.getStartColumn(),
						end: syntaxException.getStartLine(),
						source: new File(syntaxException.getSourceLocator())
					)
					
					throw new CompilationErrorException(compilationError)
				}
				
			}

			throw new CompilationErrorException(
				new CompilationError(message: 'Could not get compilation error')
			)
		}
	}

	/**
	 * the groovy compiler appears to ignore <import package.name.*> when
	 * trying to import nested static classes. Groovy considers these as
	 * 'static star imports', and needs the "import static package.org.*"
	 * syntax for them to work.
	 *
	 * Since Java files won't have this syntax,
	 * we need to do a little modification to the AST during compilation
	 * to ensure any of the compiled play-Java classes have their imports
	 * picked up. This seems like more of an interim solution though...
	 */
	def importFixer = new SourceUnitOperation() {

		// TODO: add all the relative play static star imports
		def playStaticStarImports = [
			'play.mvc.Http'
		]

		void call(SourceUnit source) throws CompilationFailedException {

			def ast = source.getAST()
			def imports = ast.getStarImports()
				.collect {
					it.getPackageName()[0..it.getPackageName().length()-2]
				}
				.findAll { 
					it in playStaticStarImports
				}

			imports.each { 
				ast.addStaticStarImport('*', ClassHelper.make(it))
			}
		}
	}
}

class ClassDefinition {
	String name
	byte[] code
	File source

	@Override String toString() {
		"ClassDefinition(name: ${name}, source: ${source})"
	}
}

@Immutable class CompilationResult {
	
	List<ClassDefinition> updatedClasses, removedClasses
}

class CompilationError {
	String message
	Integer line
	Integer start
	Integer end
	File source

	@Override String toString() {
		"CompilationError(${message}, ${line}, ${start}, ${end}, ${source})"
	}
}

class CompilationErrorException extends Exception {
	CompilationError compilationError

	def CompilationErrorException(compilationError) {
		super()
		this.compilationError = compilationError
	}
}
