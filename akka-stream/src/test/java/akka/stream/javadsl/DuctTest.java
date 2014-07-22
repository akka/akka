package akka.stream.javadsl;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import scala.concurrent.duration.FiniteDuration;
import scala.concurrent.Future;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.dispatch.Futures;
import akka.japi.Function;
import akka.japi.Function2;
import akka.japi.Pair;
import akka.japi.Predicate;
import akka.japi.Procedure;
import akka.stream.FlowMaterializer;
import akka.stream.MaterializerSettings;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;

public class DuctTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("DuctTest",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final MaterializerSettings settings = MaterializerSettings.create().withDispatcher("akka.test.stream-dispatcher");
  final FlowMaterializer materializer = FlowMaterializer.create(settings, system);

  @Test
  public void mustBeAbleToUseSimpleOperators() {
    final JavaTestKit probe = new JavaTestKit(system);
    final String[] lookup = { "a", "b", "c", "d", "e", "f" };

    Subscriber<Integer> inputSubscriber = Duct.create(Integer.class).drop(2).take(3).map(new Function<Integer, String>() {
      public String apply(Integer elem) {
        return lookup[elem];
      }
    }).filter(new Predicate<String>() {
      public boolean test(String elem) {
        return !elem.equals("c");
      }
    }).grouped(2).mapConcat(new Function<java.util.List<String>, java.util.List<String>>() {
      public java.util.List<String> apply(java.util.List<String> elem) {
        return elem;
      }
    }).fold("", new Function2<String, String, String>() {
      public String apply(String acc, String elem) {
        return acc + elem;
      }
    }).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    final java.util.Iterator<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5).iterator();
    Publisher<Integer> publisher = Flow.create(input).toPublisher(materializer);

    publisher.subscribe(inputSubscriber);

    probe.expectMsgEquals("de");
  }

  @Test
  public void mustMaterializeIntoPublisherSubscriber() {
    final JavaTestKit probe = new JavaTestKit(system);
    Pair<Subscriber<String>, Publisher<String>> inOutPair = Duct.create(String.class).build(materializer);

    Flow.create(inOutPair.second()).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);
    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));

    Publisher<String> publisher = Flow.create(Arrays.asList("a", "b", "c")).toPublisher(materializer);
    publisher.subscribe(inOutPair.first());
    probe.expectMsgEquals("a");
    probe.expectMsgEquals("b");
    probe.expectMsgEquals("c");
  }

  @Test
  public void mustProduceToSubscriber() {
    final JavaTestKit probe = new JavaTestKit(system);

    Subscriber<String> subscriber = Duct.create(String.class).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    Subscriber<String> inSubscriber = Duct.create(String.class).produceTo(materializer, subscriber);

    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));

    Publisher<String> publisher = Flow.create(Arrays.asList("a", "b", "c")).toPublisher(materializer);
    publisher.subscribe(inSubscriber);
    probe.expectMsgEquals("a");
    probe.expectMsgEquals("b");
    probe.expectMsgEquals("c");
  }

  @Test
  public void mustBeAppendableToFlow() {
    final JavaTestKit probe = new JavaTestKit(system);

    Duct<String, Void> duct = Duct.create(String.class).map(new Function<String, String>() {
      public String apply(String elem) {
        return elem.toLowerCase();
      }
    }).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    });

    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));

    Flow<String> flow = Flow.create(Arrays.asList("a", "b", "c")).map(new Function<String, String>() {
      public String apply(String elem) {
        return elem.toUpperCase();
      }
    });

    flow.append(duct).consume(materializer);

    probe.expectMsgEquals("a");
    probe.expectMsgEquals("b");
    probe.expectMsgEquals("c");
  }

  @Test
  public void mustBeAppendableToDuct() {
    final JavaTestKit probe = new JavaTestKit(system);

    Duct<String, Integer> duct1 = Duct.create(String.class).map(new Function<String, Integer>() {
      public Integer apply(String elem) {
        return Integer.parseInt(elem);
      }
    });

    Subscriber<Integer> ductInSubscriber = Duct.create(Integer.class).map(new Function<Integer, String>() {
      public String apply(Integer elem) {
        return Integer.toString(elem * 2);
      }
    }).append(duct1).map(new Function<Integer, String>() {
      public String apply(Integer elem) {
        return "elem-" + (elem + 10);
      }
    }).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    Flow.create(Arrays.asList(1, 2, 3)).produceTo(materializer, ductInSubscriber);

    probe.expectMsgEquals("elem-12");
    probe.expectMsgEquals("elem-14");
    probe.expectMsgEquals("elem-16");
  }

  @Test
  public void mustCallOnCompleteCallbackWhenDone() {
    final JavaTestKit probe = new JavaTestKit(system);

    Subscriber<Integer> inSubscriber = Duct.create(Integer.class).map(new Function<Integer, String>() {
      public String apply(Integer elem) {
        return elem.toString();
      }
    }).onComplete(materializer, new OnCompleteCallback() {
      @Override
      public void onComplete(Throwable e) {
        if (e == null)
          probe.getRef().tell("done", ActorRef.noSender());
        else
          probe.getRef().tell(e, ActorRef.noSender());
      }
    });

    Publisher<Integer> publisher = Flow.create(Arrays.asList(1, 2, 3)).toPublisher(materializer);
    publisher.subscribe(inSubscriber);
    probe.expectMsgEquals("done");
  }

  @Test
  public void mustBeAbleToUseMapFuture() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    Subscriber<String> c = Duct.create(String.class).mapFuture(new Function<String, Future<String>>() {
      public Future<String> apply(String elem) {
        return Futures.successful(elem.toUpperCase());
      }
    }).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    final java.lang.Iterable<String> input = Arrays.asList("a", "b", "c");
    Flow.create(input).produceTo(materializer, c);
    probe.expectMsgEquals("A");
    probe.expectMsgEquals("B");
    probe.expectMsgEquals("C");
  }

}
