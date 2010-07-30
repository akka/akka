package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class PojoImpl extends TypedActor implements PojoIntf {

    public String m1(String b, String h) {
        return "m1impl: " + b + " " + h;
    }

    public String m2(String b, String h) {
        return "m2impl: " + b + " " + h;
    }
}
