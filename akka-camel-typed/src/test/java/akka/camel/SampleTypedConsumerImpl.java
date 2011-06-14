package akka.camel;

/**
 * @author Martin Krasser
 */
public class SampleTypedConsumerImpl implements SampleTypedConsumer {

    public String m1(String b, String h) {
        return "m1: " + b + " " + h;
    }

    @consume("direct:m2")
    public String m2(String b, String h) {
        return "m2: " + b + " " + h;
    }

    @consume("direct:m3")
    public String m3(String b, String h) {
        return "m3: " + b + " " + h;
    }

    public String m4(String b, String h) {
        return "m4: " + b + " " + h;
    }

    public void m5(String b, String h) {
    }
}
