package akka.stream.actor;

import org.reactivestreams.Publisher;
import org.junit.ClassRule;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.stream.FlowMaterializer;
import akka.stream.MaterializerSettings;
import akka.stream.javadsl.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Flow;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;
import akka.japi.Procedure;

import static akka.stream.actor.ActorPublisherMessage.Request;

public class ActorPublisherTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  public static class TestPublisher extends UntypedActorPublisher<Integer> {

    @Override
    public void onReceive(Object msg) {
      if (msg instanceof Request) {
        onNext(1);
        onComplete();
      } else if (msg == ActorPublisherMessage.cancelInstance()) {
        getContext().stop(getSelf());
      } else {
        unhandled(msg);
      }
    }

  }

  final ActorSystem system = actorSystemResource.getSystem();

  final MaterializerSettings settings = MaterializerSettings.create(system.settings().config()).withDispatcher("akka.test.stream-dispatcher");
  final FlowMaterializer materializer = FlowMaterializer.create(settings, system);

  @Test
  public void mustHaveJavaAPI() {
    final JavaTestKit probe = new JavaTestKit(system);
    final ActorRef ref = system
        .actorOf(Props.create(TestPublisher.class).withDispatcher("akka.test.stream-dispatcher"));
    final Publisher<Integer> publisher = UntypedActorPublisher.create(ref);
    Flow.create(publisher).foreach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);
    probe.expectMsgEquals(1);
  }

}
