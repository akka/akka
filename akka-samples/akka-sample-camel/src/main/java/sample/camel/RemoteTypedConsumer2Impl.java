package sample.camel;

import akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class RemoteTypedConsumer2Impl extends TypedActor implements RemoteTypedConsumer2 {

    public String foo(String body, String header) {
        return String.format("remote2: body=%s header=%s", body, header);
    }

}
