package play.groovysupport.compiler;

import play.Logger;

import java.lang.reflect.Field;

/**
 * Helper class to clear groovy call site array.
 * It has to be in Java to access class declared fields directly,
 * without Groovy meta class.
 *
 * @author Marek Piechut <marek.piechut@gmail.com>
 */
class CallSiteRemover {

    /**
     * Remove value from static CallSite array used by groovy
     * to speed up indirect method calls via meta class.
     * It has to be removed after hotswap replaced any method in
     * class.
     *
     * @param clazz class to clear CallSite array in
     */
    static void clearCallSite(Class clazz) throws Exception {
        try {
            Field callSite = clazz.getDeclaredField("$callSiteArray");
            callSite.setAccessible(true);
            callSite.set(null, null);
        } catch (NoSuchFieldException ex) {
            Logger.debug("No $callSiteArray field in class: " + clazz.getName() + ". Not a groovy source?");
        }
    }
}
