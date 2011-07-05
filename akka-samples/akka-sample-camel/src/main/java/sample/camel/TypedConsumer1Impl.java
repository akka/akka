package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class TypedConsumer1Impl implements TypedConsumer1 {

    public void foo(String body) {
        System.out.println("Received message:");
        System.out.println(body);
    }

    public String bar(@Body String body, @Header("name") String header) {
        return String.format("body=%s header=%s", body, header);
    }
}
