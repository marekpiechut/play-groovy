package play.groovysupport

import play.classloading.enhancers.Enhancer
import play.classloading.ApplicationClasses.ApplicationClass

import javassist.CtClass
import javassist.bytecode.Descriptor
import javassist.CtNewMethod
import play.Play
import play.test.SpockTest
import java.lang.reflect.Modifier
import play.test.GebTest

/**
 * Enhance TestEngine class to find and show Geg and Spock tests
 *
 * @author Marek Piechut <marek.piechut@gmail.com>
 */
class TestRunnerEnhancer extends Enhancer {


    private static final String indexMethodBody = '''
        {
            java.util.Comparator comparator = new play.groovysupport.ClassNameComparator();

            java.util.List unitTests = play.test.TestEngine.allUnitTests();
            unitTests.addAll(play.groovysupport.TestRunnerEnhancer.getGebTests());
            java.util.Collections.sort(unitTests, comparator);

            java.util.List functionalTests = play.test.TestEngine.allFunctionalTests();
            unitTests.addAll(play.groovysupport.TestRunnerEnhancer.getSpockTests());
            java.util.Collections.sort(functionalTests, comparator);

            java.util.List seleniumTests = play.test.TestEngine.allSeleniumTests();

            renderArgs.put("unitTests", unitTests);
            renderArgs.put("functionalTests", functionalTests);
            renderArgs.put("seleniumTests", seleniumTests);
            render(new Object[0]);
        }
    '''

    @Override
    void enhanceThisClass(ApplicationClass appClass) {
        if (shouldEnhance(appClass)) {
            def cc = makeClass(appClass);
            def method = cc.getDeclaredMethod('index')
            method.setBody(indexMethodBody)
            appClass.javaClass = cc.toClass()
            appClass.enhancedByteCode == cc.toBytecode()
            cc.defrost()
        }
    }

    private boolean shouldEnhance(appClass) {
        return appClass.name == "controllers.TestRunner"
    }

    public static List<Class> getSpockTests() {
        def spockClasses = Play.classloader.getAssignableClasses(SpockTest.class)
        spockClasses = spockClasses.grep {!Modifier.isAbstract(it.getModifiers())}
        return spockClasses
    }

    public static List<Class> getGebTests() {
        def gebClasses = Play.classloader.getAssignableClasses(GebTest.class)
        gebClasses = gebClasses.grep {!Modifier.isAbstract(it.getModifiers())}
        return gebClasses
    }
}

public class ClassNameComparator implements Comparator<Class> {

    @Override
    int compare(Class o1, Class o2) {
        return o1.name.compareTo(o2.name)
    }
}
