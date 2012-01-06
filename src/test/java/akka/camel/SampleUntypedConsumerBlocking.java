package akka.camel;

import akka.util.Duration;

/**
 * @author Martin Krasser
 */
public class SampleUntypedConsumerBlocking extends UntypedConsumerActor {

    public CamelInterface camel(){
        return new Camel().start();
    }

    public String getEndpointUri() {
        return "direct:test-untyped-consumer-blocking";
    }

    public BlockingOrNot isBlocking() {
        return new Blocking(Duration.fromNanos(100000000000L));
    }

    public void onReceive(Object message) {
        Message msg = (Message)message;
        String body = msg.getBodyAs(String.class);
        String header = msg.getHeaderAs("test", String.class);
        getContext().sender().tell(String.format("%s %s", body, header));
   }

}
