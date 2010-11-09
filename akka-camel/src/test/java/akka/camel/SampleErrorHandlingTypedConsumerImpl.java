package akka.camel;

import akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class SampleErrorHandlingTypedConsumerImpl extends TypedActor implements SampleErrorHandlingTypedConsumer {

    public String willFail(String s) {
        throw new RuntimeException(String.format("error: %s", s));
    }

}
