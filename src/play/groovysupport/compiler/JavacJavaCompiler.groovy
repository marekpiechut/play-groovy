package play.groovysupport.compiler

import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilerConfiguration
import play.Play

/**
 * @author Marek Piechut <m.piechut@tt.com.pl>
 */
class JavacJavaCompiler extends org.codehaus.groovy.tools.javac.JavacJavaCompiler implements PlayJavaCompiler {

    CompilerConfiguration config
    File targetDirectory
    List<Source> sources

    JavacJavaCompiler(CompilerConfiguration config, List<Source> sources) {
        super(config)
        this.targetDirectory = config.targetDirectory
        this.config = config
        this.sources = sources
    }

    @Override
    List<ClassDefinition> getCompilationResult() {
        List<ClassDefinition> classes = new ArrayList<>()
        sources.each { Source source ->
            if (source.java) {
                List<File> binaryFiles = getBinaryFiles(source)
                binaryFiles.each { File file ->
                    String className = toClassName(file, targetDirectory)
                    ClassDefinition classDef = new ClassDefinition(className, file.bytes, source.file)
                    classDef.newClass = !Play.@classes.hasClass(className)
                    classes << classDef
                }
            }
        }

        return classes
    }

    private String toClassName(File file, File baseFolder) {
        String name = file.absolutePath.substring(baseFolder.absolutePath.length() + 1)
        name = name.substring(0, name.lastIndexOf('.'))
        name = name.replaceAll("[\\\\/]", '.')
        return name
    }

    private List<File> getBinaryFiles(Source source) {
        //Usually we won't have inner classes so size 1 looks most optimal
        List<File> binaries = new ArrayList<File>(1)
        //Class name prefix (so we find also inner classes
        String className = source.file.name[0..-6]
        String prefix = className + '$'
        className = className + '.class'
        //Path from compiler target root to class file (it's a java package but with slashes)
        String folderPath = source.file.parentFile.absolutePath.substring(source.baseFolder.absolutePath.length())
        File folder = new File(targetDirectory, folderPath)
        folder.eachFile { File file ->
            if (file.name == className || (file.name.startsWith(prefix) && file.name.endsWith(".class"))) {
                binaries << file
            }
        }

        return binaries
    }
}
