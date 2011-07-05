package sample.camel;

/**
 * @author Martin Krasser
 */
public class TypedConsumer2Impl implements TypedConsumer2 {

    public String foo(String body) {
        return String.format("default: %s", body);
    }
}
