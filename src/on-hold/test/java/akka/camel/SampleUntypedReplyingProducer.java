package akka.camel;

/**
 * @author Martin Krasser
 */
public class SampleUntypedReplyingProducer extends UntypedProducerActor {

    public String getEndpointUri() {
        return "direct:producer-test-1";
    }

}
