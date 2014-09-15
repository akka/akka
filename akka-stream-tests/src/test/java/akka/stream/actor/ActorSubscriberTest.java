package akka.stream.actor;

import org.reactivestreams.Subscriber;
import org.junit.ClassRule;
import org.junit.Test;
import java.util.Arrays;

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

import static akka.stream.actor.ActorSubscriberMessage.OnNext;
import static akka.stream.actor.ActorSubscriberMessage.OnError;

public class ActorSubscriberTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  public static class TestSubscriber extends UntypedActorSubscriber {

    final ActorRef probe;

    public TestSubscriber(ActorRef probe) {
      this.probe = probe;
    }

    @Override
    public RequestStrategy requestStrategy() {
      return ZeroRequestStrategy.getInstance();
    }

    @Override
    public void onReceive(Object msg) {
      if (msg.equals("run")) {
        request(4);
      } else if (msg instanceof OnNext) {
        probe.tell(((OnNext) msg).element(), getSelf());
      } else if (msg == ActorSubscriberMessage.onCompleteInstance()) {
        probe.tell("done", getSelf());
        getContext().stop(getSelf());
      } else if (msg instanceof OnError) {
        probe.tell("err", getSelf());
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
    final ActorRef ref = system.actorOf(Props.create(TestSubscriber.class, probe.getRef()).withDispatcher(
        "akka.test.stream-dispatcher"));
    final Subscriber<Integer> subscriber = UntypedActorSubscriber.create(ref);
    final java.util.Iterator<Integer> input = Arrays.asList(1, 2, 3).iterator();
    Flow.create(input).produceTo(subscriber, materializer);
    ref.tell("run", null);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals("done");
  }

}
