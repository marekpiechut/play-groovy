package play.groovysupport.compiler

import groovy.transform.Immutable
import javassist.ByteArrayClassPath
import javassist.ClassPool
import javassist.Modifier
import javassist.bytecode.Opcode
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
        def compilerOptions = ['source': sourceVersion, 'target': sourceVersion, 'keepStubs': true, 'stubDir': stubsFolder]
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
                def bytes = clearGroovyStamps(cn, newClasses[cn].bytes)
                new ClassDefinition(name: cn,
                        code: bytes, source: classNameToSource(cn))
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
     * Do some crazy bytecode modifications on generated groovy code
     * to allow hotswap of recompiled groovy sources.
     * <p/>
     * We have to remove static field generated by groovy compiler
     * that changes name with each recompile (contains compilation timestamp in it's name)
     * to allow hotswap of groovy bytecode in JVM. Other way we get error that class
     * schama is changed.
     * <p/>
     * This code also removes initialization of this variable in static code
     * so app does not break when it tries to load modified class.
     *
     * @param name name of class
     * @param bytes compiled class data
     * @return
     */
    def clearGroovyStamps(name, bytes) {
        def cp = ClassPool.getDefault()
        cp.insertClassPath(new ByteArrayClassPath(name, bytes))
        def cc = cp.get(name)
        def oldFieldName
        for (field in cc.declaredFields) {
            //This generated groovy field
            if (field.name.startsWith("__timeStamp__")) {
                oldFieldName = field.name
                //Let's rename it to something constant and make private
                field.setModifiers(Modifier.PRIVATE)
                field.setName("__timeStamp__")
                break;
            }
        }

        if (oldFieldName) {
            //Ok field was found and renamed, let's clean the initializer

            def initializer = cc.classInitializer;
            def mi = initializer.getMethodInfo();
            def ca = mi.getCodeAttribute();

            def ci = ca.iterator()
            //Let's check all byte code operations in static initializer
            while (ci.hasNext()) {
                def index = ci.next()
                int op = ci.byteAt(index)
                //PUTSTATIC is a byte code instruction to assign value from stack to static variable
                if (op == Opcode.PUTSTATIC) {
                    //Address of target variable is calculated like this
                    def targetFieldAddr = (ci.byteAt(index + 1) << 8) + ci.byteAt(index + 2)
                    def fieldrefName = mi.getConstPool().getFieldrefName(targetFieldAddr)
                    if (fieldrefName == oldFieldName) {
                        //Ok, so it's an assignment to renamed variable
                        //Let's change assignment to pop from stack (POP2 -> pop long/double value)
                        //We have to remove it or stack won't be valid
                        ci.writeByte((byte) Opcode.POP2, index);
                        //PUTSTATIC takes 2 arguments so we have to clear them out or
                        //they will be used as byte code instructions and probably invalidate class
                        ci.writeByte((byte) Opcode.NOP, index + 1);
                        ci.writeByte((byte) Opcode.NOP, index + 2);
                    }
                }

            }
        }

        cc.defrost()
        cc.detach()

        return cc.toBytecode()
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
                it.getPackageName()[0..it.getPackageName().length() - 2]
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

    @Override
    String toString() {
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
