package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;
import akka.camel.consume;

/**
 * @author Martin Krasser
 */
public interface TypedConsumer2 {

    @consume("direct:default")
    public String foo(String body);
}
