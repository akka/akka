package sample.camel.route;

import akka.actor.ActorRef;
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;

public class RouteConsumer extends UntypedConsumerActor {
  private ActorRef transformer;

  public RouteConsumer(ActorRef transformer) {
    this.transformer = transformer;
  }

  public String getEndpointUri() {
    return "jetty:http://0.0.0.0:8877/camel/welcome";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      // Forward a string representation of the message body to transformer
      String body = camelMessage.getBodyAs(String.class, getCamelContext());
      transformer.forward(camelMessage.withBody(body), getContext());
    } else
      unhandled(message);
  }
}
