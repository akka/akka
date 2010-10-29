package sample.camel;

import akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class TypedConsumer2Impl extends TypedActor implements TypedConsumer2 {

    public String foo(String body) {
        return String.format("default: %s", body);
    }
}
