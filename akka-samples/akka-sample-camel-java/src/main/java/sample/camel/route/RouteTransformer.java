package sample.camel.route;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.camel.CamelMessage;
import akka.dispatch.Mapper;

public class RouteTransformer extends UntypedActor {
  private ActorRef producer;

  public RouteTransformer(ActorRef producer) {
    this.producer = producer;
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      // example: transform message body "foo" to "- foo -" and forward result
      // to producer
      CamelMessage camelMessage = (CamelMessage) message;
      CamelMessage transformedMessage = camelMessage.mapBody(new Mapper<String, String>() {
        @Override
        public String apply(String body) {
          return String.format("- %s -", body);
        }
      });
      producer.forward(transformedMessage, getContext());
    } else
      unhandled(message);
  }
}
