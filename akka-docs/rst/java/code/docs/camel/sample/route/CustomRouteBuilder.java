package docs.camel.sample.route;

//#CustomRouteExample
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

public class CustomRouteBuilder extends RouteBuilder{
  public void configure() throws Exception {
    from("direct:welcome").process(new Processor(){
      public void process(Exchange exchange) throws Exception {
        exchange.getOut().setBody(String.format("Welcome %s",
          exchange.getIn().getBody()));
      }
    });
  }
}
//#CustomRouteExample
