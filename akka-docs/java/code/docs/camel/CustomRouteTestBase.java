package docs.camel;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.camel.Camel;
import akka.camel.CamelExtension;

public class CustomRouteTestBase {
  public void customRoute() throws Exception{
    //#CustomRoute
    ActorSystem system = ActorSystem.create("some-system");
    Camel camel = CamelExtension.get(system);
    ActorRef responder = system.actorOf(new Props(Responder.class), "TestResponder");
    camel.context().addRoutes(new CustomRouteBuilder(responder));
    //#CustomRoute
    system.stop(responder);
    system.shutdown();
  }
}
