package se.scalablesolutions.akka.camel;

import se.scalablesolutions.akka.camel.UntypedConsumerActor;

/**
 * @author Martin Krasser
 */
public class SampleUntypedConsumer extends UntypedConsumerActor {

    public String getEndpointUri() {
        return "direct:test-untyped-consumer";
    }

    public void onReceive(Object message) {
        Message msg = (Message)message;
        String body = msg.bodyAs(String.class);
        String header = msg.headerAs("test", String.class);
        getContext().replySafe(String.format("%s %s", body, header));
   }

}
