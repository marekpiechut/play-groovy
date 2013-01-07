package play.groovysupport

import javassist.CtClass
import javassist.CtField
import play.Logger
import play.classloading.ApplicationClasses.ApplicationClass
import play.classloading.enhancers.Enhancer

import static javassist.bytecode.AccessFlag.*

/**
 * @author Marek Piechut <m.piechut@tt.com.pl>
 */
class EnsureStaticClassInfoEnhancer extends Enhancer {
    @Override
    void enhanceThisClass(ApplicationClass appClass) {
        if (appClass.javaFile.name.endsWith('.groovy')) {
            def cc = makeClass(appClass)
            if (!cc.packageName?.startsWith('play.')) {
                appClass.enhancedByteCode = ensureClassInfo(cc)

                cc.defrost()
            }
        }
    }

    private byte[] ensureClassInfo(CtClass cc) {
        for (field in cc.declaredFields) {
            if (field.name.equals('$staticClassInfo$')) {
                Logger.trace("\$staticClassInfo\$ already defined in ${cc.name}")
                return cc.toBytecode()
            }
        }

        Logger.trace("\$staticClassInfo\$ not defined in ${cc.name}. Adding dummy field to fix hotswap")
        CtClass type = classPool.get("org.codehaus.groovy.reflection.ClassInfo")
        def field = new CtField(type, '$staticClassInfo$', cc)
        field.modifiers = STATIC | PRIVATE | SYNTHETIC
        cc.addField(field)

        return cc.toBytecode()
    }
}
