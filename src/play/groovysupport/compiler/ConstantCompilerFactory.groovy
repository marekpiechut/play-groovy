package play.groovysupport.compiler

import org.codehaus.groovy.tools.javac.JavaCompilerFactory
import org.codehaus.groovy.tools.javac.JavaCompiler
import org.codehaus.groovy.control.CompilerConfiguration

class ConstantCompilerFactory implements JavaCompilerFactory {

    JavaCompiler compiler

    ConstantCompilerFactory(JavaCompiler compiler) {
        this.compiler = compiler
    }

    @Override
    JavaCompiler createCompiler(CompilerConfiguration config) {
        return compiler
    }
}
