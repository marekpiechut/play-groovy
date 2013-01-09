package play.groovysupport.compiler

import org.codehaus.groovy.tools.javac.JavaCompilerFactory
import org.codehaus.groovy.tools.javac.JavaCompiler
import org.codehaus.groovy.control.CompilerConfiguration

private class ConstantCompilerFactory implements JavaCompilerFactory {

    EcjJavaCompiler compiler

    ConstantCompilerFactory(EcjJavaCompiler compiler) {
        this.compiler = compiler
    }

    @Override
    JavaCompiler createCompiler(CompilerConfiguration config) {
        return compiler
    }
}
