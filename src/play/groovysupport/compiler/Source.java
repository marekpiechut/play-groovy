package play.groovysupport.compiler;

import groovy.transform.ToString;

import java.io.File;

/**
 * @author Marek Piechut <m.piechut@tt.com.pl>
 */
@ToString
public class Source {
    File baseFolder;
    File file;

    public Source(File baseFolder, File file) {
        this.baseFolder = baseFolder;
        this.file = file;
    }
}
