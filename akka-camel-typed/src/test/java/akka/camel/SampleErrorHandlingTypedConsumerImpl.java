package akka.camel;

/**
 * @author Martin Krasser
 */
public class SampleErrorHandlingTypedConsumerImpl implements SampleErrorHandlingTypedConsumer {

    public String willFail(String s) {
        throw new RuntimeException(String.format("error: %s", s));
    }

}
