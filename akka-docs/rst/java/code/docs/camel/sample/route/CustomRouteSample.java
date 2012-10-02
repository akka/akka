package docs.camel.sample.route;

import akka.actor.*;
import akka.camel.CamelExtension;

public class CustomRouteSample {
  public static void main(String[] args) {
    try {
      //#CustomRouteExample
      // the below lines can be added to a Boot class, so that you can run the
      // example from a MicroKernel
      ActorSystem system = ActorSystem.create("some-system");
      final ActorRef producer = system.actorOf(new Props(Producer1.class));
      final ActorRef mediator = system.actorOf(new Props(new UntypedActorFactory() {
        public Actor create() {
          return new Transformer(producer);

        }
      }));
      ActorRef consumer = system.actorOf(new Props(new UntypedActorFactory() {
        public Actor create() {
          return new Consumer3(mediator);

        }
      }));
      CamelExtension.get(system).context().addRoutes(new CustomRouteBuilder());
      //#CustomRouteExample
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
