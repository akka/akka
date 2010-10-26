package akka.camel;

import akka.camel.RemoteUntypedConsumerActor;

/**
 * @author Martin Krasser
 */
public class SampleRemoteUntypedConsumer extends RemoteUntypedConsumerActor {

    public SampleRemoteUntypedConsumer() {
        this("localhost", 7774);
    }

    public SampleRemoteUntypedConsumer(String host, int port) {
        super(host, port);
    }

    public String getEndpointUri() {
        return "direct:remote-untyped-consumer";
    }

    public void onReceive(Object message) {
        Message msg = (Message)message;
        String body = msg.getBodyAs(String.class);
        String header = msg.getHeaderAs("test", String.class);
        getContext().replySafe(String.format("%s %s", body, header));
   }

}
