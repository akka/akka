package akka.stream.actor;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.stream.MaterializerSettings;
import akka.stream.javadsl.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.scaladsl2.FlowMaterializer;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Subscriber;

import java.util.Arrays;

import static akka.stream.actor.ActorSubscriberMessage.OnError;
import static akka.stream.actor.ActorSubscriberMessage.OnNext;

@Ignore
public class ActorSubscriberTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest", AkkaSpec.testConf());

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

  final MaterializerSettings settings = new MaterializerSettings(2, 4, 2, 4, "akka.test.stream-dispatcher");
  final FlowMaterializer materializer = FlowMaterializer.create(settings, system);

  @Test
  public void mustHaveJavaAPI() {
    final JavaTestKit probe = new JavaTestKit(system);
    final ActorRef ref = system.actorOf(Props.create(TestSubscriber.class, probe.getRef()).withDispatcher("akka.test.stream-dispatcher"));
    final Subscriber<Integer> subscriber = UntypedActorSubscriber.create(ref);
    final java.util.Iterator<Integer> input = Arrays.asList(1, 2, 3).iterator();

    Source.from(input).runWith(Sink.subscriber(subscriber), materializer);

    ref.tell("run", null);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals("done");
  }

}
