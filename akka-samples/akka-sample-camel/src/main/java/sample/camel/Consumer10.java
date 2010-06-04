package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public class Consumer10 {

    @consume("file:data/input2")
    public void foo(String body) {
        System.out.println("Received message:");
        System.out.println(body);
    }

    @consume("jetty:http://0.0.0.0:8877/camel/active")
    public String bar(@Body String body, @Header("name") String header) {
        return String.format("body=%s header=%s", body, header);
    }

}
