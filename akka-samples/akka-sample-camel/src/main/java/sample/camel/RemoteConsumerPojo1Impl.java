package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;
import se.scalablesolutions.akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class RemoteConsumerPojo1Impl extends TypedActor implements RemoteConsumerPojo1 {

    @consume("jetty:http://localhost:6644/camel/remote-active-object-1")
    public String foo(@Body String body, @Header("name") String header) {
        return String.format("remote1: body=%s header=%s", body, header);
    }
}
