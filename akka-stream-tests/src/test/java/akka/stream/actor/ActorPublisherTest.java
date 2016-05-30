package akka.stream.actor;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.stream.StreamTest;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Source;
import akka.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;

import static akka.stream.actor.ActorPublisherMessage.Request;

public class ActorPublisherTest extends StreamTest {
  public ActorPublisherTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("ActorPublisherTest", AkkaSpec.testConf());

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

  @Test
  public void mustHaveJavaAPI() {
    final JavaTestKit probe = new JavaTestKit(system);
    final ActorRef ref = system
      .actorOf(Props.create(TestPublisher.class).withDispatcher("akka.test.stream-dispatcher"));
    final Publisher<Integer> publisher = UntypedActorPublisher.create(ref);
    Source.fromPublisher(publisher)
      .runForeach(new akka.japi.function.Procedure<Integer>() {
        private static final long serialVersionUID = 1L;
        @Override
        public void apply(Integer elem) throws Exception {
          probe.getRef().tell(elem, ActorRef.noSender());
        }
      }, materializer);
    probe.expectMsgEquals(1);
  }

}
