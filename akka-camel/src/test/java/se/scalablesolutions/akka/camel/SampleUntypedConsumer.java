package se.scalablesolutions.akka.camel;

/**
 * @author Martin Krasser
 */
public class SampleUntypedConsumer extends UntypedConsumer {

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
