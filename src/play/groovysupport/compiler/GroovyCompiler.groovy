package play.groovysupport.compiler

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit
import play.Logger
import play.Play
import play.classloading.ApplicationClasses.ApplicationClass
import play.classloading.ApplicationClassloader
import play.exceptions.CompilationException
import play.vfs.VirtualFile

import java.util.regex.Matcher
import java.util.regex.Pattern

class GroovyCompiler {

    def compilerConf
    def groovyClassLoader
    boolean useEcj = true

    def GroovyCompiler(CompilerConfiguration configuration) {
        compilerConf = configuration
        String compiler = Play.configuration.getProperty("play.groovy.java.compiler", "javac")
        Logger.info("Using $compiler java compiler")
        useEcj = compiler == "ecj"
        groovyClassLoader = new GroovyClassLoader(new CompilerClassLoader(), compilerConf)
    }

    synchronized Collection<ClassDefinition> update(List<Source> sources) {
        Logger.debug("Compiling Groovy and Java classes: ${sources*.file.name}")
        //Performance: Groovy groovyCompiler also executes javac and compiles all Java classes
        //Maybe we could get them somehow instead of executing ECJ (Play groovyCompiler)
        //or use ECJ also here and don't process java files in second compilation
        def cu = new JavaAwareCompilationUnit(compilerConf, groovyClassLoader)
        def javaCompiler = useEcj ? new EcjJavaCompiler() : new JavacJavaCompiler(compilerConf, sources)
        cu.compilerFactory = new ConstantCompilerFactory(javaCompiler)
        cu.addSources(sources*.file as File[])

        try {
            cu.compile()

            def sourceFileMap = new HashMap(sources.size())
            for (sourceUnit in cu) {
                sourceUnit.getAST().classes.each { clazz ->
                    def filename = sourceUnit.name
                    sourceFileMap[clazz.name] = new File(filename)
                }
            }

            def classes = new ArrayList<ClassDefinition>(javaCompiler.compilationResult.size() + cu.classes.size())

            cu.classes.each {
                //We map sources by outer class name so we have to substring inner classes
                def sourceName = it.name.contains('$') ? it.name[0..it.name.indexOf('$') - 1] : it.name
                def sourceFile = sourceFileMap[sourceName]
                def classDef = new ClassDefinition(it.name, it.bytes, sourceFile)
                classDef.newClass = !Play.@classes.hasClass(it.name)
                classes << classDef
            }

            classes += javaCompiler.compilationResult

            return classes

        } catch (MultipleCompilationErrorsException e) {

            if (e.getErrorCollector().getLastError() != null) {

                def errorMessage = e.getErrorCollector().getLastError()// as SyntaxErrorMessage

                if (errorMessage instanceof SimpleMessage) {
                    CompilationException exception = parseCompilationError(errorMessage)
                    throw exception
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

    private static final Pattern JAVAC_ERROR_PATTERN = Pattern.compile(
            /.*?javac.*?\s+(?<file>[^\s]+\.java):(?<line>\d+):\s+error:(?<error>.*?)\s+\^\s.*/,
            Pattern.DOTALL)

    private CompilationException parseCompilationError(SimpleMessage compilationError) {
        String message = compilationError.message
        Matcher matcher = JAVAC_ERROR_PATTERN.matcher(message)
        if (matcher.matches()) {
            String fileName = matcher.group("file")
            String line = matcher.group("line")
            String problem = matcher.group("error")
            if (fileName) {
                VirtualFile source = VirtualFile.open(fileName)
                int lineNo = line as int
                return new CompilationException(source, problem, lineNo, 0, 0)
            }
        }

        return new CompilationException(message)
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

