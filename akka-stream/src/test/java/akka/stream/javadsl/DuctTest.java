package akka.stream.javadsl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

import org.reactivestreams.api.Consumer;
import org.reactivestreams.api.Producer;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.japi.Function;
import akka.japi.Function2;
import akka.japi.Procedure;
import akka.japi.Util;
import akka.stream.FlowMaterializer;
import akka.stream.MaterializerSettings;
import akka.stream.Transformer;
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

    Consumer<Integer> inputConsumer = Duct.create(Integer.class).drop(2).take(3).map(new Function<Integer, String>() {
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
    Producer<Integer> producer = Flow.create(input).toProducer(materializer);

    producer.produceTo(inputConsumer);

    probe.expectMsgEquals("de");
  }

  @Test
  public void mustMaterializeIntoProducerConsumer() {
    final JavaTestKit probe = new JavaTestKit(system);
    Pair<Consumer<String>, Producer<String>> inOutPair = Duct.create(String.class).build(materializer);

    Flow.create(inOutPair.second()).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);
    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));

    Producer<String> producer = Flow.create(Arrays.asList("a", "b", "c")).toProducer(materializer);
    producer.produceTo(inOutPair.first());
    probe.expectMsgEquals("a");
    probe.expectMsgEquals("b");
    probe.expectMsgEquals("c");
  }

  @Test
  public void mustProduceToConsumer() {
    final JavaTestKit probe = new JavaTestKit(system);

    Consumer<String> consumer = Duct.create(String.class).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    Consumer<String> inConsumer = Duct.create(String.class).produceTo(materializer, consumer);

    probe.expectNoMsg(FiniteDuration.create(200, TimeUnit.MILLISECONDS));

    Producer<String> producer = Flow.create(Arrays.asList("a", "b", "c")).toProducer(materializer);
    producer.produceTo(inConsumer);
    probe.expectMsgEquals("a");
    probe.expectMsgEquals("b");
    probe.expectMsgEquals("c");
  }

  @Test
  public void mustCallOnCompleteCallbackWhenDone() {
    final JavaTestKit probe = new JavaTestKit(system);

    Consumer<Integer> inConsumer = Duct.create(Integer.class).map(new Function<Integer, String>() {
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

    Producer<Integer> producer = Flow.create(Arrays.asList(1, 2, 3)).toProducer(materializer);
    producer.produceTo(inConsumer);
    probe.expectMsgEquals("done");
  }

}
