package docs.camel.sample.http;

import akka.actor.ActorRef;
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedProducerActor;
import org.apache.camel.Exchange;

import java.util.HashSet;
import java.util.Set;

//#HttpExample
public class HttpProducer extends UntypedProducerActor{
  private ActorRef transformer;

  public HttpProducer(ActorRef transformer) {
    this.transformer = transformer;
  }

  public String getEndpointUri() {
    return "jetty://http://akka.io/?bridgeEndpoint=true";
  }

  @Override
  public Object onTransformOutgoingMessage(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      Set<String> httpPath = new HashSet<String>();
      httpPath.add(Exchange.HTTP_PATH);
      return camelMessage.withHeaders(camelMessage.getHeaders(httpPath));
    } else return super.onTransformOutgoingMessage(message);
  }

  @Override
  public void onRouteResponse(Object message) {
    transformer.forward(message, getContext());
  }
}
//#HttpExample