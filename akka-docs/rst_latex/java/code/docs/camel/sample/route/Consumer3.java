package docs.camel.sample.route;

//#CustomRouteExample
import akka.actor.ActorRef;
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;

public class Consumer3 extends UntypedConsumerActor{
  private ActorRef transformer;

  public Consumer3(ActorRef transformer){
    this.transformer = transformer;
  }

  public String getEndpointUri() {
    return "jetty:http://0.0.0.0:8877/camel/welcome";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      transformer.forward(camelMessage.getBodyAs(String.class, getCamelContext()),getContext());
    } else
      unhandled(message);
  }
}
//#CustomRouteExample
