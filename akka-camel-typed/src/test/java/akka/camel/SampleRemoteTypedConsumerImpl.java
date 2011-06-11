package akka.camel;

/**
 * @author Martin Krasser
 */
public class SampleRemoteTypedConsumerImpl implements SampleRemoteTypedConsumer {

    public String foo(String s) {
        return String.format("remote typed actor: %s", s);
    }

}
