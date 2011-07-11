package sample.camel;

import akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class RemoteTypedConsumer1Impl implements RemoteTypedConsumer1 {

    public String foo(String body, String header) {
        return String.format("remote1: body=%s header=%s", body, header);
    }
}
