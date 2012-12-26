package play.groovysupport.compiler

import org.codehaus.groovy.control.CompilerConfiguration
import play.Play
import javax.annotation.processing.Processor

/**
 * Groovy groovyCompiler configuration using Play framework defaults and
 * Play configuration to setup groovy groovyCompiler so it's compatible
 * with Play.
 *
 * @author Marek Piechut <marek.piechut@gmail.com>
 */
class PlayGroovyCompilerConfiguration extends CompilerConfiguration {

    PlayGroovyCompilerConfiguration() {
        sourceEncoding = 'UTF-8'
        recompileGroovySource = false
        debug = true

        def sourceVersion = Play.configuration.get('java.source', '1.5')

        //Classpath
        def classpath = System.getProperty('java.class.path').split(System.getProperty('path.separator')) as List
        classpathList = classpath

        //Output to Play tmp/classes - place where Play puts classes
        def targetDirectory = new File(Play.tmpDir, 'classes/')
        targetDirectory.mkdirs()

        this.targetDirectory = targetDirectory

        def stubsFolder = new File(Play.tmpDir, 'groovy_stubs')
        stubsFolder.mkdirs()

        //Java annotation processors
        def processorsLoader = ServiceLoader.load(Processor.class, Play.class.classLoader)
        def processors = processorsLoader.iterator()*.class.name.join(',')
        def namedValues = ['encoding', sourceEncoding]

        if (processors) {
            namedValues << 'processor' << processors
        }

        //Options for Javac groovyCompiler used internally by Groovy groovyCompiler to handle java code
        def compilerOptions = ['source': sourceVersion, 'target': sourceVersion,
                'keepStubs': true, 'stubDir': stubsFolder,
                'namedValues': namedValues as String[]]
        jointCompilationOptions = compilerOptions
    }

    @Override
    String toString() {
        StringBuilder sb = new StringBuilder("PlayGroovyCompilerConfiguration")
        sb << '\n\t' << "sourceEncoding" << ': ' << sourceEncoding
        sb << '\n\t' << "recompileGroovySource" << ': ' << recompileGroovySource
        sb << '\n\t' << "debug" << ': ' << debug
        sb << '\n\t' << "classpath" << ': ' << classpath
        sb << '\n\t' << "targetDirectory" << ': ' << targetDirectory
        sb << '\n\t' << "jointCompilationOptions" << ': ' << jointCompilationOptions
    }
}
