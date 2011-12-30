package akka.camel;

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
        String body = msg.getBodyAs(String.class);
        CamelContextManager.getMandatoryTemplate().sendBody("direct:forward-test-1", body);
    }
}
