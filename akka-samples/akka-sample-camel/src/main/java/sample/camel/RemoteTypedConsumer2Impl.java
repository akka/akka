package sample.camel;

/**
 * @author Martin Krasser
 */
public class RemoteTypedConsumer2Impl implements RemoteTypedConsumer2 {

    public String foo(String body, String header) {
        return String.format("remote2: body=%s header=%s", body, header);
    }

}
