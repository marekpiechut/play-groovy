package play.groovysupport.compiler;

import groovy.transform.ToString;

import java.io.File;

/**
 * @author Marek Piechut <marek.piechut@gmail.com>
 */
public class Source {

    public enum Language {
        JAVA, GROOVY
    }

    File baseFolder
    File file
    Language lang

    public Source(File baseFolder, File file, Language lang) {
        this.baseFolder = baseFolder
        this.file = file
        this.lang = lang
    }

    @Override
    String toString() {
        return file?.getName()
    }
}
