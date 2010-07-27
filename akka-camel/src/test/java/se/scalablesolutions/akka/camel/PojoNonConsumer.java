package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.actor.*;

/**
 * @author Martin Krasser
 */
public class PojoNonConsumer extends TypedActor implements PojoNonConsumerIntf {

    public String foo(String s) {
        return String.format("foo: %s", s);
    }

}