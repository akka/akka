package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class TypedRemoteConsumer1Impl extends TypedActor implements TypedRemoteConsumer1 {

    public String foo(@Body String body, @Header("name") String header) {
        return String.format("remote1: body=%s header=%s", body, header);
    }
}
