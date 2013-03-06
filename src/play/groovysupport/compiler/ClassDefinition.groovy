package play.groovysupport.compiler

import play.classloading.ApplicationClasses.ApplicationClass

/**
 * @author Marek Piechut <marek.piechut@gmail.com>
 */
class ClassDefinition {
    String name
    byte[] code
    File source
    boolean newClass
    ApplicationClass appClass

    ClassDefinition(String name, byte[] code, File source) {
        this.code = code
        this.name = name
        this.source = source
    }

    boolean isGroovy() {
        source?.name.endsWith('.groovy')
    }

    @Override
    String toString() {
        return "ClassDefinition: $name, compiled: ${code != null}, isNew: $newClass"
    }
}
