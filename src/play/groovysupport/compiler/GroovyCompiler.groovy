package play.groovysupport.compiler

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit
import play.classloading.ApplicationClasses.ApplicationClass
import play.classloading.ApplicationClassloader
import play.exceptions.CompilationException
import play.vfs.VirtualFile

class GroovyCompiler {

    def compilerConf
    def groovyClassLoader

    def GroovyCompiler(CompilerConfiguration configuration) {
        compilerConf = configuration
        groovyClassLoader = new GroovyClassLoader(new CompilerClassLoader(), compilerConf)
    }

    def update(List<Source> sources) {
        //Performance: Groovy groovyCompiler also executes javac and compiles all Java classes
        //Maybe we could get them somehow instead of executing ECJ (Play groovyCompiler)
        //or use ECJ also here and don't process java files in second compilation
        def cu = new JavaAwareCompilationUnit(compilerConf, groovyClassLoader)
        cu.addSources(sources*.file as File[])

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
                newClasses[it.name] = new ClassDefinition(it.name, it.bytes, sourceFile)
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
                    def syntaxException = errorMessage.cause

                    throw new CompilationException(
                            VirtualFile.open(syntaxException.sourceLocator), syntaxException.message,
                            syntaxException.line, syntaxException.startColumn, syntaxException.endColumn
                    )
                } else {
                    throw new CompilationException(errorMessage.cause ? errorMessage.cause.message : errorMessage.toString())
                }
            }

            throw new CompilationException(e.message)
        }
    }

    /**
     * Need to override Play application classloader to stop it
     * from compiling classes using OOTB Play compiler.
     *
     * Need to use it though to make sure classes loaded by Play
     * and by plugin have same classloader (are equal)
     */
    private class CompilerClassLoader extends ApplicationClassloader {

        /**
         * Just try to find class. Don't try to compile it as
         * we're already in middle of compilation.
         *
         * @param name
         * @return
         */
        @Override
        public Class<?> loadApplicationClass(String name) {
            if (ApplicationClass.isClass(name)) {
                Class maybeAlreadyLoaded = findLoadedClass(name);
                if (maybeAlreadyLoaded != null) {
                    return maybeAlreadyLoaded;
                }
            }
        }
    }
}

