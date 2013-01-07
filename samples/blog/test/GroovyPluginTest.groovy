import play.test.SpockTest
import play.Play
import play.classloading.ApplicationClasses.ApplicationClass

/**
 * @author Marek Piechut <m.piechut@tt.com.pl>
 */
class GroovyPluginTest extends SpockTest {

    File java = new File("../app/TestJavaClass.java")
    File javaSource = new File("TestJavaClass.j")
    File groovy = new File("../app/TestGroovyClass.groovy")
    File groovySource = new File ("TestGroovyClass.g")

    def setup() {
        java << javaSource.text
        groovy << groovySource.text
    }

    def cleanup() {
        java.delete()
        groovy.delete()
    }

    def "deleted class should be removed from app classes"() {
        Play.pluginCollection.compileSources()
        ApplicationClass javaClass = Play.@classes.getApplicationClass("TestJavaClass")
        ApplicationClass groovyClass = Play.@classes.getApplicationClass("TestGroovyClass")
        assert javaClass : "Could not find Java class after compilation"
        assert groovyClass : "Could not find Groovy class after compilation"

        java.delete()
        groovy.delete()

        Play.pluginCollection.compileSources()

        javaClass = Play.@classes.getApplicationClass("TestJavaClass")
        groovyClass = Play.@classes.getApplicationClass("TestGroovyClass")
        assert javaClass: "Java class was not removed after compilation"
        assert groovyClass: "Groovy class was not removed after compilation"
    }
}
