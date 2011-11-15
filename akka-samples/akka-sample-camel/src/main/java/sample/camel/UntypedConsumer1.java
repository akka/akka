package sample.camel;

import akka.camel.Message;
import akka.camel.UntypedConsumerActor;

/**
 * @author Martin Krasser
 */
public class UntypedConsumer1 extends UntypedConsumerActor {

    public String getEndpointUri() {
        return "direct:untyped-consumer-1";
    }

    public void onReceive(Object message) {
        Message msg = (Message)message;
        String body = msg.getBodyAs(String.class);
        sender.tell(String.format("received %s", body));
   }
}
