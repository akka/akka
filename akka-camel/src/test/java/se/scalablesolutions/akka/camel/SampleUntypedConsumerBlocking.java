package se.scalablesolutions.akka.camel;

/**
 * @author Martin Krasser
 */
public class SampleUntypedConsumerBlocking extends UntypedConsumerActor {

    public String getEndpointUri() {
        return "direct:test-untyped-consumer-blocking";
    }

    public boolean isBlocking() {
        return true;
    }

    public void onReceive(Object message) {
        Message msg = (Message)message;
        String body = msg.bodyAs(String.class);
        String header = msg.headerAs("test", String.class);
        getContext().replySafe(String.format("%s %s", body, header));
   }
    
}
