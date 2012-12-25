package play.groovysupport.compiler

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit
import play.Logger
import play.Play

import static org.codehaus.groovy.control.CompilationUnit.SourceUnitOperation
import javax.annotation.processing.Processor

class GroovyCompiler {

    def compilerConf
    def prevClasses = [:]
    def classesToSources = [:]
    def groovyClassLoader

    def GroovyCompiler(CompilerConfiguration configuration) {
        compilerConf = configuration
        groovyClassLoader = new GroovyClassLoader(Play.classloader, compilerConf)
    }

    def classNameToSource(name) {
        if (name.contains('$')) {
            // inner classes will be in the same file as
            // their parent class, so look on that instead
            name = name.substring(0, name.indexOf('$'))
        }
        return classesToSources[name]
    }

    def update(List sources) {
        //Performance: Groovy compiler also executes javac and compiles all Java classes
        //Maybe we could get them somehow instead of executing ECJ (Play compiler)
        //or use ECJ also here and don't process java files in second compilation
        def cu = new JavaAwareCompilationUnit(compilerConf, groovyClassLoader)

        // reset classesToSources map
        classesToSources = [:]

        // fix static star imports, see comment on field
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

            prevClasses = newClasses

            return updated

        } catch (MultipleCompilationErrorsException e) {

            if (e.getErrorCollector().getLastError() != null) {

                def errorMessage = e.getErrorCollector().getLastError()// as SyntaxErrorMessage
                if (errorMessage instanceof SimpleMessage) {
                    // TODO: this shouldn't happen but handle it somehow
                    // just in case
                    println 'This is really bad and should not have happened'
                    e.printStackTrace()
                    System.exit(1)
                } else if (errorMessage instanceof SyntaxErrorMessage) {
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
                } else {
                    throw errorMessage.getCause()
                }
            }

            throw new CompilationErrorException(
                    new CompilationError(e)
            )
        }
    }
}

class ClassDefinition {
    String name
    byte[] code
    File source

    @Override
    String toString() {
        "ClassDefinition(name: ${name}, source: ${source})"
    }
}

class CompilationError {
    String message
    Integer line
    Integer start
    Integer end
    File source

    @Override
    String toString() {
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
