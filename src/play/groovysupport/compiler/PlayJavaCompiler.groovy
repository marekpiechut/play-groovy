package play.groovysupport.compiler

import org.codehaus.groovy.tools.javac.JavaCompiler

/**
 * @author Marek Piechut <m.piechut@tt.com.pl>
 */
public interface PlayJavaCompiler extends JavaCompiler {

    List<ClassDefinition> getCompilationResult()
}
