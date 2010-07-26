package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.TypedActor;
import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public class ConsumerPojo2Impl extends TypedActor implements ConsumerPojo2 {

    @consume("direct:default")
    public String foo(String body) {
        return String.format("default: %s", body);
    }
}