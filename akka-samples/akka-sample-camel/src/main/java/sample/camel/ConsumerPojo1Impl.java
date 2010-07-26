package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;
import se.scalablesolutions.akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class ConsumerPojo1Impl extends TypedActor implements ConsumerPojo1 {

    @consume("file:data/input/pojo")
    public void foo(String body) {
        System.out.println("Received message:");
        System.out.println(body);
    }

    @consume("jetty:http://0.0.0.0:8877/camel/pojo")
    public String bar(@Body String body, @Header("name") String header) {
        return String.format("body=%s header=%s", body, header);
    }
}
