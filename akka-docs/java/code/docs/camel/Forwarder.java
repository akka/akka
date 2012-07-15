package docs.camel;
//#RouteResponse
import akka.actor.ActorRef;
import akka.camel.javaapi.UntypedProducerActor;

public class Forwarder extends UntypedProducerActor {
  private String uri;
  private ActorRef target;

  public Forwarder(String uri, ActorRef target) {
    this.uri = uri;
    this.target = target;
  }
  public String getEndpointUri() {
    return uri;
  }

  @Override
  public void onRouteResponse(Object message) {
    target.forward(message,getContext());
  }
}
//#RouteResponse
