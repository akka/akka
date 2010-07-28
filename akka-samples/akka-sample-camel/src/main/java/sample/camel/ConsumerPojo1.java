package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public interface ConsumerPojo1 {
    @consume("file:data/input/pojo")
    public void foo(String body);
    
    @consume("jetty:http://0.0.0.0:8877/camel/pojo")
    public String bar(@Body String body, @Header("name") String header);
}
