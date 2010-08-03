package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class PojoBase extends TypedActor implements PojoBaseIntf {

    public String m1(String b, String h) {
        return "m1base: " + b + " " + h;
    }

    public String m2(String b, String h) {
        return "m2base: " + b + " " + h;
    }

    public String m3(String b, String h) {
        return "m3base: " + b + " " + h;
    }

    public String m4(String b, String h) {
        return "m4base: " + b + " " + h;
    }

    public void m5(String b, String h) {
    }
}
