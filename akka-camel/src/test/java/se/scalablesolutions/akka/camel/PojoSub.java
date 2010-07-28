package se.scalablesolutions.akka.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;
import se.scalablesolutions.akka.actor.*;

public class PojoSub extends PojoBase implements PojoSubIntf {

    @Override
    @consume("direct:m1sub")
    public String m1(@Body String b, @Header("test") String h) {
        return "m1sub: " + b + " " + h;
    }

    @Override
    public String m2(String b, String h) {
        return "m2sub: " + b + " " + h;
    }

    @Override
    @consume("direct:m3sub")
    public String m3(@Body String b, @Header("test") String h) {
        return "m3sub: " + b + " " + h;
    }
    
}
