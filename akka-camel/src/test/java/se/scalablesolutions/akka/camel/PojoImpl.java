package se.scalablesolutions.akka.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;
import se.scalablesolutions.akka.actor.*;

/**
 * @author Martin Krasser
 */
public class PojoImpl extends TypedActor implements PojoIntf {

    public String m1(String b, String h) {
        return "m1impl: " + b + " " + h;
    }

    @consume("direct:m2impl")
    public String m2(@Body String b, @Header("test") String h) {
        return "m2impl: " + b + " " + h;
    }
}
