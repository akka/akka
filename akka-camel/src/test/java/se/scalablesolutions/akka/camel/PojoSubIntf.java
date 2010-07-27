package se.scalablesolutions.akka.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;

public interface PojoSubIntf extends PojoBaseIntf {
    @consume("direct:m1sub")
    public String m1(@Body String b, @Header("test") String h);

    @Override
    public String m2(String b, String h);

    @Override
    @consume("direct:m3sub")
    public String m3(@Body String b, @Header("test") String h);
}
