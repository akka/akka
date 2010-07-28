package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public interface RemoteConsumerPojo1 {

    @consume("jetty:http://localhost:6644/camel/remote-active-object-1")
    public String foo(@Body String body, @Header("name") String header);
}
