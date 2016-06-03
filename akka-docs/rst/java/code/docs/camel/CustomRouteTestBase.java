package docs.camel;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import akka.testkit.JavaTestKit;

public class CustomRouteTestBase {
  public void customRoute() throws Exception{
    //#CustomRoute
    ActorSystem system = ActorSystem.create("some-system");
    try {
      Camel camel = CamelExtension.get(system);
      ActorRef responder = system.actorOf(Props.create(Responder.class), "TestResponder");
      camel.context().addRoutes(new CustomRouteBuilder(responder));
      //#CustomRoute
    } finally {
      JavaTestKit.shutdownActorSystem(system);
    }
  }
}
