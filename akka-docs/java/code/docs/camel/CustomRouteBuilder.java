package docs.camel;
//#CustomRoute
import akka.actor.ActorRef;
import akka.camel.internal.component.CamelPath;
import org.apache.camel.builder.RouteBuilder;

public class CustomRouteBuilder extends RouteBuilder{
  private ActorRef responder;

  public CustomRouteBuilder(ActorRef responder) {
    this.responder = responder;
  }

  public void configure() throws Exception {
    from("jetty:http://localhost:8877/camel/custom").to(CamelPath.toCamelUri(responder));
  }
}
//#CustomRoute
