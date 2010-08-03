package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.TypedActor;

public class PojoSub extends PojoBase implements PojoSubIntf {

    @Override
    public String m1(String b, String h) {
        return "m1sub: " + b + " " + h;
    }

    @Override
    public String m2(String b, String h) {
        return "m2sub: " + b + " " + h;
    }

    @Override
    public String m3(String b, String h) {
        return "m3sub: " + b + " " + h;
    }
    
}
