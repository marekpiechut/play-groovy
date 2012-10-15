package controllers;

import play.mvc.Controller;
import play.mvc.Util;

class Test extends Controller {

    @Util
    public static void javaUtil () {
        System.out.println("Java util");
    }
}
