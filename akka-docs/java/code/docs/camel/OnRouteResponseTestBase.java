package docs.camel;

import akka.actor.*;
import org.junit.Test;

public class OnRouteResponseTestBase {

  public void onRouteResponse(){
    //#RouteResponse
    ActorSystem system = ActorSystem.create("some-system");
    Props receiverProps = new Props(ResponseReceiver.class);
    final ActorRef receiver = system.actorOf(receiverProps,"responseReceiver");
    UntypedActorFactory factory = new UntypedActorFactory() {
      public Actor create() {
        return new Forwarder("http://localhost:8080/news/akka", receiver);
      }
    };
    ActorRef forwardResponse = system.actorOf(new Props(factory));
    // the Forwarder sends out a request to the web page and forwards the response to
    // the ResponseReceiver
    forwardResponse.tell("some request");
    //#RouteResponse
    system.stop(receiver);
    system.stop(forwardResponse);
    system.shutdown();
  }
}
