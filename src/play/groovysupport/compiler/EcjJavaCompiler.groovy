package play.groovysupport.compiler

import play.Play
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.tools.javac.JavaCompiler
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions
import org.eclipse.jdt.internal.compiler.Compiler
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration
import org.eclipse.jdt.internal.compiler.CompilationResult
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit

import org.eclipse.jdt.internal.compiler.env.INameEnvironment
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import play.classloading.ApplicationClasses.ApplicationClass
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException
import play.exceptions.UnexpectedException
import play.classloading.ApplicationClasses
import org.eclipse.jdt.internal.compiler.ICompilerRequestor
import org.eclipse.jdt.core.compiler.IProblem
import play.exceptions.CompilationException
import org.eclipse.jdt.internal.compiler.ClassFile
import play.Logger
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies
import org.eclipse.jdt.internal.compiler.IProblemFactory
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory
import play.vfs.VirtualFile

/**
 * @author Marek Piechut <m.piechut@tt.com.pl>
 */
class EcjJavaCompiler implements PlayJavaCompiler {

    static Map compilerSettings
    static Map packagesCache

    List<ClassDefinition> compilationResult = []

    static {
        packagesCache = new HashMap()

        compilerSettings = new HashMap()
        compilerSettings.put(CompilerOptions.OPTION_ReportMissingSerialVersion, CompilerOptions.IGNORE)
        compilerSettings.put(CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.GENERATE)
        compilerSettings.put(CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.GENERATE)
        compilerSettings.put(CompilerOptions.OPTION_ReportDeprecation, CompilerOptions.IGNORE)
        compilerSettings.put(CompilerOptions.OPTION_ReportUnusedImport, CompilerOptions.IGNORE)
        compilerSettings.put(CompilerOptions.OPTION_Encoding, "UTF-8")
        compilerSettings.put(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE)

        String javaVersion = Play.configuration.get("java.source", CompilerOptions.VERSION_1_5)
        compilerSettings.put(CompilerOptions.OPTION_Source, javaVersion)
        compilerSettings.put(CompilerOptions.OPTION_TargetPlatform, javaVersion)
        compilerSettings.put(CompilerOptions.OPTION_PreserveUnusedLocal, CompilerOptions.PRESERVE)
        compilerSettings.put(CompilerOptions.OPTION_Compliance, javaVersion)
    }

    EcjJavaCompiler() {
    }

    @Override
    void compile(List<String> sources, CompilationUnit cu) {
        File stubsDir = cu.configuration.jointCompilationOptions['stubDir']
        File outputDir = cu.configuration.targetDirectory

        def toCompile = sources.collect {new PlayCompilationUnit(new File(it))}
        toCompile += cu.stubGenerator.toCompile.collect {new PlayCompilationUnit(new File(stubsDir, it + ".java"), true)}

        IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.exitOnFirstError();
        IProblemFactory problemFactory = new DefaultProblemFactory(Locale.ENGLISH);

        def nameEnvironment = new PlayNameEnvironment(Play.@classes)
        def compilerRequestor = new PlayCompilerRequestor(outputDir)

        Compiler compiler = new Compiler(nameEnvironment, policy, compilerSettings, compilerRequestor, problemFactory) {

            @Override
            protected void handleInternalException(Throwable e, CompilationUnitDeclaration ud, CompilationResult result) {
            }
        };

        compiler.compile(toCompile as ICompilationUnit[]);
    }

    static class PlayCompilationUnit implements ICompilationUnit {

        File source
        boolean stub

        PlayCompilationUnit(File source, boolean stub = false) {
            this.source = source
            this.stub = stub
        }

        @Override
        char[] getContents() {
            return source.text as char[]
        }

        @Override
        char[] getMainTypeName() {
            return null
        }

        @Override
        char[][] getPackageName() {
            return null
        }

        @Override
        char[] getFileName() {
            return source.name
        }
    }

    static class PlayNameEnvironment implements INameEnvironment {

        ApplicationClasses applicationClasses

        PlayNameEnvironment(ApplicationClasses applicationClasses) {
            this.applicationClasses = applicationClasses
        }

        public NameEnvironmentAnswer findType(final char[][] compoundTypeName) {
            final StringBuffer result = new StringBuffer();
            for (int i = 0; i < compoundTypeName.length; i++) {
                if (i != 0) {
                    result.append('.');
                }
                result.append(compoundTypeName[i]);
            }
            return findLogicType(result.toString());
        }

        public NameEnvironmentAnswer findType(final char[] typeName, final char[][] packageName) {
            final StringBuffer result = new StringBuffer();
            for (int i = 0; i < packageName.length; i++) {
                result.append(packageName[i]);
                result.append('.');
            }
            result.append(typeName);
            return findLogicType(result.toString());
        }

        private NameEnvironmentAnswer findLogicType(final String name) {
            try {

                if (name.startsWith("play.") || name.startsWith("java.") || name.startsWith("javax.")) {
                    byte[] bytes = Play.classloader.getClassDefinition(name);
                    if (bytes != null) {
                        ClassFileReader classFileReader = new ClassFileReader(bytes, name.toCharArray(), true);
                        return new NameEnvironmentAnswer(classFileReader, null);
                    }
                    return null;
                }

                char[] fileName = name.toCharArray();
                ApplicationClass applicationClass = applicationClasses.getApplicationClass(name);

                // ApplicationClass exists
                if (applicationClass != null) {

                    if (applicationClass.javaByteCode != null) {
                        ClassFileReader classFileReader = new ClassFileReader(applicationClass.javaByteCode, fileName, true);
                        return new NameEnvironmentAnswer(classFileReader, null);
                    }
                    // Cascade compilation
                    ICompilationUnit compilationUnit = new CompilationUnit(name);
                    return new NameEnvironmentAnswer(compilationUnit, null);
                }

                // So it's a standard class
                byte[] bytes = Play.classloader.getClassDefinition(name);
                if (bytes != null) {
                    ClassFileReader classFileReader = new ClassFileReader(bytes, fileName, true);
                    return new NameEnvironmentAnswer(classFileReader, null);
                }

                // So it does not exist
                return null;
            } catch (ClassFormatException e) {
                // Something very very bad
                throw new UnexpectedException(e);
            }
        }

        public boolean isPackage(char[][] parentPackageName, char[] packageName) {
            // Rebuild something usable
            StringBuilder sb = new StringBuilder();
            if (parentPackageName != null) {
                for (char[] p : parentPackageName) {
                    sb.append(new String(p));
                    sb.append(".");
                }
            }
            sb.append(new String(packageName));
            String name = sb.toString();
            if (packagesCache.containsKey(name)) {
                return packagesCache.get(name).booleanValue();
            }
            // Check if thera a .java or .class for this ressource
            if (Play.classloader.getClassDefinition(name) != null) {
                packagesCache.put(name, false);
                return false;
            }
            if (applicationClasses.getApplicationClass(name) != null) {
                packagesCache.put(name, false);
                return false;
            }
            packagesCache.put(name, true);
            return true;
        }

        public void cleanup() {
        }
    }

    class PlayCompilerRequestor implements ICompilerRequestor {

        File outputDir

        PlayCompilerRequestor(File outputDir) {
            this.outputDir = outputDir
        }

        public void acceptResult(CompilationResult result) {
            // If error
            if (result.hasErrors()) {
                for (IProblem problem : result.getErrors()) {
                    String className = new String(problem.getOriginatingFileName()).replace("/", ".");
                    className = className.substring(0, className.length() - 5);
                    String message = problem.getMessage();
                    if (problem.getID() == IProblem.CannotImportPackage) {
                        // Non sense !
                        message = problem.getArguments()[0] + " cannot be resolved";
                    }
                    throw new CompilationException(VirtualFile.open(result.compilationUnit.source), message, problem.getSourceLineNumber(), problem.getSourceStart(), problem.getSourceEnd());
                }
            }
            // Something has been compiled
            ClassFile[] clazzFiles = result.getClassFiles();
            for (int i = 0; i < clazzFiles.length; i++) {
                final ClassFile clazzFile = clazzFiles[i];
                final char[][] compoundName = clazzFile.getCompoundName();
                final StringBuffer clazzName = new StringBuffer();
                for (int j = 0; j < compoundName.length; j++) {
                    if (j != 0) {
                        clazzName.append('.');
                    }
                    clazzName.append(compoundName[j]);
                }

                if (Logger.isTraceEnabled()) {
                    Logger.trace("Compiled %s", clazzName);
                }

                if (!result.compilationUnit.stub) {

                    File sourceFile = result.compilationUnit.source
                    def classDef = new ClassDefinition(clazzName.toString(), clazzFile.bytes, sourceFile)

                    File targetFile = toClassFile(outputDir, clazzName.toString())
                    def updated = !targetFile.exists() || sourceFile.lastModified() > targetFile.lastModified()

                    targetFile.parentFile.mkdirs()
                    targetFile.createNewFile()
                    targetFile << clazzFile.bytes

                    classDef.newClass = !Play.@classes.hasClass(clazzName.toString())

                    if (classDef.newClass || updated) {
                        compilationResult << classDef
                    }
                }
            }
        }
    }

    private File toClassFile(File dir, String clazzName) {
        File targetFile = new File(dir, clazzName.replaceAll(/\./, '/') + ".class")
        return targetFile
    }
}
