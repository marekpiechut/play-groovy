package play.groovysupport.compiler

import groovy.transform.InheritConstructors
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit
import play.Play
import play.exceptions.CompilationException
import play.vfs.VirtualFile

class GroovyCompiler {

    def compilerConf
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
        cu.addSources(sources as File[])

        try {

            def newClasses = [:]

            cu.compile()

            def sourceFileMap = new HashMap(sources.size())
            for (sourceUnit in cu) {
                sourceUnit.getAST().classes.each { clazz ->
                    def filename = sourceUnit.name
                    sourceFileMap[clazz.name] = new File(filename)
                }
            }

            cu.classes.each {
                //We map sources by outer class name so we have to substring inner classes
                def sourceName = it.name.contains('$') ? it.name[0..it.name.indexOf('$') - 1] : it.name
                def sourceFile = sourceFileMap[sourceName]
                newClasses[it.name] = new ClassDefinition(name: it.name, code: it.bytes, source: sourceFile)
            }

            return newClasses.values()

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
                    def syntaxException = errorMessage.cause

                    throw new CompilationException(
                            VirtualFile.open(syntaxException.sourceLocator), syntaxException.message,
                            syntaxException.line, syntaxException.startColumn, syntaxException.endColumn
                    )
                } else {
                    throw errorMessage.cause
                }
            }

            throw new CompilationException(e.message)
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

