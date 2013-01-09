package play.groovysupport.compiler

import play.Play
import play.Logger

/**
 * @author Marek Piechut <marek.piechut@gmail.com>
 */
class PlayDelegateCompiler {

    private def toClassName(baseFolder, file) {
        def path = file.absolutePath
        def name = path.substring(baseFolder.absolutePath.length() + 1, path.lastIndexOf('.'))
        name = name.replaceAll('[/\\\\]', '.')
        return name
    }

    def update(List<Source> sources) {
        Logger.debug("Compiling java classes: ${sources*.file.name}")
        def compiled = []
        def sourceFiles = sources*.file
        def modified = new HashSet()
        modified.addAll(Play.@classes.all().grep { sourceFiles.contains(it.javaFile.realFile) })
        def loadedClasses = Play.@classes.all()*.javaFile.realFile
        def newFiles = sources.grep { !loadedClasses.contains(it.file) }
        newFiles.each {
            if (!loadedClasses.contains(it.file)) {
                def appClass = Play.@classes.getApplicationClass(toClassName(it.baseFolder, it.file))
                modified << appClass
            }
        }

        modified.each {
            it.refresh()
            if (it.compile()) {
                def classDef = new ClassDefinition(it.name, it.javaByteCode, it.javaFile.realFile)
                classDef.newClass = !Play.@classes.hasClass(it.name)
                compiled << classDef
            } else {
                Play.@classes.remove(it)
            }
        }
        return compiled
    }
}
