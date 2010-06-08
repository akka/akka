package se.scalablesolutions.akka.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public interface PojoIntf {

    public String m1(String b, String h);

    @consume("direct:m2intf")
    public String m2(@Body String b, @Header("test") String h);

}
