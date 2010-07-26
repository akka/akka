package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;
import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public interface ConsumerPojo2 {

    @consume("direct:default")
    public String foo(String body);
}