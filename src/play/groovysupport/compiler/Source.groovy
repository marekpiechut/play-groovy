package play.groovysupport.compiler;

/**
 * @author Marek Piechut <marek.piechut@gmail.com>
 */
public class Source {

    File baseFolder
    File file

    public Source(File baseFolder, File file) {
        this.baseFolder = baseFolder
        this.file = file
    }

    @Override
    String toString() {
        return file?.getName()
    }

    boolean isJava() {
        file.name.endsWith('.java')
    }

    boolean isGroovy() {
        file.name.endsWith('.groovy')
    }
}
