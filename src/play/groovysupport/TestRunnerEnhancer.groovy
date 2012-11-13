package play.groovysupport

import javassist.CtClass
import javassist.CtField
import play.Play
import play.classloading.ApplicationClasses.ApplicationClass
import play.classloading.enhancers.Enhancer
import play.test.GebTest
import play.test.SpockTest
import javassist.Modifier

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
            unitTests.addAll(play.groovysupport.TestRunnerEnhancer.getSpockTests());
            java.util.Collections.sort(unitTests, comparator);

            java.util.List functionalTests = play.test.TestEngine.allFunctionalTests();
            unitTests.addAll(play.groovysupport.TestRunnerEnhancer.getGebTests());
            java.util.Collections.sort(functionalTests, comparator);

            java.util.List seleniumTests = play.test.TestEngine.allSeleniumTests();

            renderArgs.put("unitTests", unitTests);
            renderArgs.put("functionalTests", functionalTests);
            renderArgs.put("seleniumTests", seleniumTests);
            render(new Object[0]);
        }
    '''

    private static final String listMethodBody = '''
        {
            java.util.Comparator comparator = new play.groovysupport.ClassNameComparator();
            java.io.StringWriter list = new java.io.StringWriter();
            java.io.PrintWriter p = new java.io.PrintWriter(list);
            p.println("---");
            p.println(play.Play.getFile("test-result").getAbsolutePath());
            p.println(play.mvc.Router.reverse(((play.vfs.VirtualFile)play.Play.modules.get("_testrunner"))
                .child("/public/test-runner/selenium/TestRunner.html")));
            java.util.List unitTests = play.test.TestEngine.allUnitTests();
            unitTests.addAll(play.groovysupport.TestRunnerEnhancer.getSpockTests());
            java.util.Collections.sort(unitTests, comparator);
            for (java.util.Iterator iterator = unitTests.iterator(); iterator.hasNext(); ) {
                Class c = (Class) iterator.next();
                p.println(c.getName() + ".class");
            }

            java.util.List funcTests = play.test.TestEngine.allFunctionalTests();
            funcTests.addAll(play.groovysupport.TestRunnerEnhancer.getGebTests());
            java.util.Collections.sort(funcTests, comparator);
            for (java.util.Iterator iterator = funcTests.iterator(); iterator.hasNext(); ) {
                Class c = (Class) iterator.next();
                p.println(c.getName() + ".class");
            }

            java.util.List seleniumTest = play.test.TestEngine.allSeleniumTests();
            for (java.util.Iterator iterator = seleniumTest.iterator(); iterator.hasNext(); ) {
                String c = (String) iterator.next();
                p.println(c);
            }
            renderText(list);
        }
    '''

    @Override
    void enhanceThisClass(ApplicationClass appClass) {
        if (shouldEnhance(appClass)) {
            def cc = makeClass(appClass);
            def indexMethod = cc.getDeclaredMethod('index')
            indexMethod.setBody(indexMethodBody)
            def listMethod = cc.getDeclaredMethod('list')
            listMethod.setBody(listMethodBody)
            if (!appClass.javaClass) {
                appClass.javaClass = cc.toClass()
            }
            appClass.enhancedByteCode == cc.toBytecode()
            cc.defrost()
        }
    }

    private boolean shouldEnhance(appClass) {
        return appClass.name == "controllers.TestRunner"
    }

    public static List<Class> getSpockTests() {
        def spockClasses = Play.classloader.getAssignableClasses(SpockTest.class)
        spockClasses = spockClasses.grep {!Modifier.isAbstract(it.getModifiers()) && !it.isAssignableFrom(GebTest.class)}
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
