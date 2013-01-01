package play.groovysupport.compiler

import groovy.transform.ToString
import play.classloading.ApplicationClasses.ApplicationClass
import play.Play
import play.vfs.VirtualFile

/**
 * @author Marek Piechut <marek.piechut@gmail.com>
 */
@ToString
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
}
