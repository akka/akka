package akka.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import akka.camel.consume;

/**
 * @author Martin Krasser
 */
public interface SampleTypedConsumer {

    public String m1(String b, String h);
    public String m2(@Body String b, @Header("test") String h);
    public String m3(@Body String b, @Header("test") String h);

    @consume("direct:m4")
    public String m4(@Body String b, @Header("test") String h);
    public void m5(@Body String b, @Header("test") String h);
}
