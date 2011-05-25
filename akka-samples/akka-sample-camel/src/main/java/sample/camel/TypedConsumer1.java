package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import akka.camel.consume;

/**
 * @author Martin Krasser
 */
public interface TypedConsumer1 {
    @consume("file:data/input/typed-actor")
    public void foo(String body);

    @consume("jetty:http://0.0.0.0:8877/camel/typed-actor")
    public String bar(@Body String body, @Header("name") String header);
}
