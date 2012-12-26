package play.groovysupport.compiler

/**
 * @author Marek Piechut <m.piechut@tt.com.pl>
 */
class ClassDefinition {
    String name
    byte[] code
    File source

    ClassDefinition(String name, byte[] code, File source) {
        this.code = code
        this.name = name
        this.source = source
    }

    @Override
    String toString() {
        "ClassDefinition(name: ${name}, source: ${source})"
    }
}
