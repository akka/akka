package docs.camel;
//#CustomRoute
import akka.actor.ActorRef;
import akka.camel.internal.component.CamelPath;
import org.apache.camel.builder.RouteBuilder;

public class CustomRouteBuilder extends RouteBuilder{
  private String uri;

  public CustomRouteBuilder(ActorRef responder) {
    uri = CamelPath.toUri(responder);
  }

  public void configure() throws Exception {
    from("jetty:http://localhost:8877/camel/custom").to(uri);
  }
}
//#CustomRoute
