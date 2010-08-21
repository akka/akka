package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.camel.consume;

/**
 * @author Martin Krasser
 */
public interface SampleTypedSingleConsumer {

    @consume("direct:foo")
    public void foo(String b);

}
