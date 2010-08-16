package se.scalablesolutions.akka.camel;

/**
 * @author Martin Krasser
 */
public class SampleUntypedForwardingProducer extends UntypedProducerActor {

    public String getEndpointUri() {
        return "direct:producer-test-1";
    }

    @Override
    public void onReceiveAfterProduce(Object message) {
        Message msg = (Message)message;
        String body = msg.bodyAs(String.class);
        CamelContextManager.template().sendBody("direct:forward-test-1", body);
    }
}
