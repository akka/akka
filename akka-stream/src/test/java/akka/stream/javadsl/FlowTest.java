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
import akka.stream.RecoveryTransformer;
import akka.stream.Transformer;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;

public class FlowTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("StashJavaAPI",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final MaterializerSettings settings = MaterializerSettings.create();
  final FlowMaterializer materializer = FlowMaterializer.create(settings, system);

  @Test
  public void mustBeAbleToUseSimpleOperators() {
    final JavaTestKit probe = new JavaTestKit(system);
    final String[] lookup = { "a", "b", "c", "d", "e", "f" };
    final java.util.Iterator<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5).iterator();
    Flow.create(input).drop(2).take(3).map(new Function<Integer, String>() {
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

    probe.expectMsgEquals("de");

  }

  @Test
  public void mustBeAbleToUseTransform() {
    final JavaTestKit probe = new JavaTestKit(system);
    final JavaTestKit probe2 = new JavaTestKit(system);
    final java.lang.Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);
    // duplicate each element, stop after 4 elements, and emit sum to the end
    Flow.create(input).transform(new Transformer<Integer, Integer>() {
      int sum = 0;
      int count = 0;

      @Override
      public scala.collection.immutable.Seq<Integer> onNext(Integer element) {
        sum += element;
        count += 1;
        return Util.immutableSeq(new Integer[] { element, element });
      }

      @Override
      public boolean isComplete() {
        return count == 4;
      }

      @Override
      public scala.collection.immutable.Seq<Integer> onComplete() {
        return Util.immutableSingletonSeq(sum);
      }

      @Override
      public void cleanup() {
        probe2.getRef().tell("cleanup", ActorRef.noSender());
      }
    }).foreach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    probe.expectMsgEquals(0);
    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals(6);
    probe2.expectMsgEquals("cleanup");
  }

  @Test
  public void mustBeAbleToUseTransformRecover() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5);
    Flow.create(input).map(new Function<Integer, Integer>() {
      public Integer apply(Integer elem) {
        if (elem == 4)
          throw new IllegalArgumentException("4 not allowed");
        else
          return elem + elem;
      }
    }).transformRecover(new RecoveryTransformer<Integer, String>() {

      @Override
      public scala.collection.immutable.Seq<String> onNext(Integer element) {
        return Util.immutableSingletonSeq(element.toString());
      }

      @Override
      public scala.collection.immutable.Seq<String> onErrorRecover(Throwable e) {
        return Util.immutableSingletonSeq(e.getMessage());
      }

      @Override
      public boolean isComplete() {
        return false;
      }

      @Override
      public scala.collection.immutable.Seq<String> onComplete() {
        return Util.immutableSeq(new String[0]);
      }

      @Override
      public void cleanup() {
      }

    }).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    probe.expectMsgEquals("0");
    probe.expectMsgEquals("2");
    probe.expectMsgEquals("4");
    probe.expectMsgEquals("6");
    probe.expectMsgEquals("4 not allowed");
  }

  @Test
  public void mustBeAbleToUseGroupBy() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input = Arrays.asList("Aaa", "Abb", "Bcc", "Cdd", "Cee");
    Flow.create(input).groupBy(new Function<String, String>() {
      public String apply(String elem) {
        return elem.substring(0, 1);
      }
    }).foreach(new Procedure<Pair<String, Producer<String>>>() {
      public void apply(final Pair<String, Producer<String>> pair) {
        Flow.create(pair.b()).foreach(new Procedure<String>() {
          public void apply(String elem) {
            probe.getRef().tell(new Pair<String, String>(pair.a(), elem), ActorRef.noSender());
          }
        }).consume(materializer);
      }
    }).consume(materializer);

    Map<String, List<String>> grouped = new HashMap<String, List<String>>();
    for (Object o : probe.receiveN(5)) {
      @SuppressWarnings("unchecked")
      Pair<String, String> p = (Pair<String, String>) o;
      List<String> g = grouped.get(p.a());
      if (g == null)
        g = new ArrayList<String>();
      g.add(p.b());
      grouped.put(p.a(), g);
    }

    assertEquals(Arrays.asList("Aaa", "Abb"), grouped.get("A"));

  }

  @Test
  public void mustBeAbleToUseSplitWhen() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input = Arrays.asList("A", "B", "C", "\n", "D", "\n", "E", "F");
    Flow.create(input).splitWhen(new Predicate<String>() {
      public boolean test(String elem) {
        return elem.equals("\n");
      }
    }).foreach(new Procedure<Producer<String>>() {
      public void apply(Producer<String> subProducer) {
        Flow.create(subProducer).filter(new Predicate<String>() {
          public boolean test(String elem) {
            return !elem.equals("\n");
          }
        }).grouped(10).foreach(new Procedure<List<String>>() {
          public void apply(List<String> chunk) {
            probe.getRef().tell(chunk, ActorRef.noSender());
          }
        }).consume(materializer);
      }
    }).consume(materializer);

    for (Object o : probe.receiveN(3)) {
      @SuppressWarnings("unchecked")
      List<String> chunk = (List<String>) o;
      if (chunk.get(0).equals("A"))
        assertEquals(Arrays.asList("A", "B", "C"), chunk);
      else if (chunk.get(0).equals("D"))
        assertEquals(Arrays.asList("D"), chunk);
      else if (chunk.get(0).equals("E"))
        assertEquals(Arrays.asList("E", "F"), chunk);
      else
        assertEquals("[A, B, C] or [D] or [E, F]", chunk);
    }

  }

  @Test
  public void mustBeAbleToUseMerge() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final java.lang.Iterable<String> input2 = Arrays.asList("D", "E", "F");
    Flow.create(input1).merge(Flow.create(input2).toProducer(materializer)).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    Set<Object> output = new HashSet<Object>(Arrays.asList(probe.receiveN(6)));
    assertEquals(new HashSet<Object>(Arrays.asList("A", "B", "C", "D", "E", "F")), output);
  }

  @Test
  public void mustBeAbleToUseZip() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final java.lang.Iterable<Integer> input2 = Arrays.asList(1, 2, 3);
    Flow.create(input1).zip(Flow.create(input2).toProducer(materializer))
        .foreach(new Procedure<Pair<String, Integer>>() {
          public void apply(Pair<String, Integer> elem) {
            probe.getRef().tell(elem, ActorRef.noSender());
          }
        }).consume(materializer);

    List<Object> output = Arrays.asList(probe.receiveN(3));
    @SuppressWarnings("unchecked")
    List<Pair<String, Integer>> expected = Arrays.asList(new Pair<String, Integer>("A", 1), new Pair<String, Integer>(
        "B", 2), new Pair<String, Integer>("C", 3));
    assertEquals(expected, output);
  }

  @Test
  public void mustBeAbleToUseConcat() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final java.lang.Iterable<String> input2 = Arrays.asList("D", "E", "F");
    Flow.create(input1).concat(Flow.create(input2).toProducer(materializer)).foreach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    List<Object> output = Arrays.asList(probe.receiveN(6));
    assertEquals(Arrays.asList("A", "B", "C", "D", "E", "F"), output);
  }

  @Test
  public void mustBeAbleToUseCallableInput() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Callable<Integer> input = new Callable<Integer>() {
      int countdown = 5;

      @Override
      public Integer call() {
        if (countdown == 0)
          throw akka.stream.Stop.getInstance();
        else {
          countdown -= 1;
          return countdown;
        }
      }
    };
    Flow.create(input).foreach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }).consume(materializer);

    List<Object> output = Arrays.asList(probe.receiveN(5));
    assertEquals(Arrays.asList(4, 3, 2, 1, 0), output);
    probe.expectNoMsg(FiniteDuration.create(500, TimeUnit.MILLISECONDS));
  }

  @Test
  public void mustBeAbleToUseOnCompleteSuccess() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input = Arrays.asList("A", "B", "C");
    Flow.create(input).onComplete(materializer, new OnCompleteCallback() {
      @Override
      public void onComplete(Throwable e) {
        if (e == null)
          probe.getRef().tell("done", ActorRef.noSender());
        else
          probe.getRef().tell(e, ActorRef.noSender());
      }
    });

    probe.expectMsgEquals("done");
  }

  @Test
  public void mustBeAbleToUseOnCompleteError() {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input = Arrays.asList("A", "B", "C");
    Flow.create(input).map(new Function<String, String>() {
      public String apply(String arg0) throws Exception {
        throw new RuntimeException("simulated err");
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

    probe.expectMsgEquals("simulated err");
  }

  @Test
  public void mustBeAbleToUseToFuture() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final java.lang.Iterable<String> input = Arrays.asList("A", "B", "C");
    Future<String> future = Flow.create(input).toFuture(materializer);
    String result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals("A", result);
  }

}
