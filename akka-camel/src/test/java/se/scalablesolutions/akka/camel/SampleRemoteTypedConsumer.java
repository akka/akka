package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.camel.consume;

/**
 * @author Martin Krasser
 */
public interface SampleRemoteTypedConsumer {

    @consume("direct:remote-typed-consumer")
    public String foo(String s);
}
