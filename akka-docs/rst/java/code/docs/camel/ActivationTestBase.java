package docs.camel;
//#CamelActivation
    import akka.actor.ActorRef;
    import akka.actor.ActorSystem;
    import akka.actor.Props;
    import akka.camel.Camel;
    import akka.camel.CamelExtension;
    import akka.camel.javaapi.UntypedConsumerActor;
    import akka.util.Timeout;
    import scala.concurrent.Future;
    import scala.concurrent.duration.Duration;
    import scala.concurrent.duration.FiniteDuration;
    import static java.util.concurrent.TimeUnit.SECONDS;
//#CamelActivation

import org.junit.Test;

public class ActivationTestBase {

  @Test
  public void testActivation() {
    //#CamelActivation

    // ..
    ActorSystem system = ActorSystem.create("some-system");
    Props props = new Props(MyConsumer.class);
    ActorRef producer = system.actorOf(props,"myproducer");
    Camel camel = CamelExtension.get(system);
    // get a future reference to the activation of the endpoint of the Consumer Actor
    Timeout timeout = new Timeout(Duration.create(10, SECONDS));
    Future<ActorRef> activationFuture = camel.activationFutureFor(producer,
      timeout, system.dispatcher());
    //#CamelActivation
    //#CamelDeactivation
    // ..
    system.stop(producer);
    // get a future reference to the deactivation of the endpoint of the Consumer Actor
    Future<ActorRef> deactivationFuture = camel.deactivationFutureFor(producer,
      timeout, system.dispatcher());
    //#CamelDeactivation
    system.shutdown();
  }

  public static class MyConsumer extends UntypedConsumerActor {
    public String getEndpointUri() {
      return "direct:test";
    }

    public void onReceive(Object message) {
    }
  }
}
