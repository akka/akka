package docs.camel.sample.route;

import akka.actor.*;
import akka.camel.CamelExtension;

public class CustomRouteSample {
  @SuppressWarnings("unused")
  public static void main(String[] args) {
    try {
      //#CustomRouteExample
      // the below lines can be added to a Boot class, so that you can run the
      // example from a MicroKernel
      ActorSystem system = ActorSystem.create("some-system");
      final ActorRef producer = system.actorOf(Props.create(Producer1.class));
      final ActorRef mediator = system.actorOf(Props.create(Transformer.class, producer));
      final ActorRef consumer = system.actorOf(Props.create(Consumer3.class, mediator));
      CamelExtension.get(system).context().addRoutes(new CustomRouteBuilder());
      //#CustomRouteExample
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
