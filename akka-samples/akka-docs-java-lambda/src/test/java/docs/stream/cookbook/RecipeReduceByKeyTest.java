/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.japi.function.Function;
import akka.japi.function.Function2;
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.runtime.BoxedUnit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class RecipeReduceByKeyTest extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeLoggingElements");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      {
        final Source<String, BoxedUnit> words = Source.from(Arrays.asList("hello", "world", "and", "hello", "akka"));

        //#word-count
        final int MAXIMUM_DISTINCT_WORDS = 1000;
        
        final Source<Pair<String, Integer>, BoxedUnit> counts = words
            // split the words into separate streams first
          .groupBy(MAXIMUM_DISTINCT_WORDS, i -> i)
          // add counting logic to the streams
          .fold(new Pair<>("", 0), (pair, elem) -> new Pair<>(elem, pair.second() + 1))
          // get a stream of word counts
          .mergeSubstreams();
        //#word-count

        final Future<List<Pair<String, Integer>>> f = counts.grouped(10).runWith(Sink.head(), mat);
        final Set<Pair<String, Integer>> result = Await.result(f, getRemainingTime()).stream().collect(Collectors.toSet());
        final Set<Pair<String, Integer>> expected = new HashSet<>();
        expected.add(new Pair<>("hello", 2));
        expected.add(new Pair<>("world", 1));
        expected.add(new Pair<>("and", 1));
        expected.add(new Pair<>("akka", 1));
        Assert.assertEquals(expected, result);
      }
    };
  }

  //#reduce-by-key-general
  static public <In, K, Out> Flow<In, Pair<K, Out>, BoxedUnit> reduceByKey(
      int maximumGroupSize,
      Function<In, K> groupKey,
      Function<K, Out> foldZero,
      Function2<Out, In, Out> fold,
      Materializer mat) {

    return Flow.<In> create()
      .groupBy(maximumGroupSize, i -> i)
      .fold((Pair<K, Out>) null, (pair, elem) -> {
        final K key = groupKey.apply(elem);
        if (pair == null) return new Pair<>(key, fold.apply(foldZero.apply(key), elem));
        else return new Pair<>(key, fold.apply(pair.second(), elem));
      })
      .mergeSubstreams();
  }
  //#reduce-by-key-general

  @Test
  public void workGeneralised() throws Exception {
    new JavaTestKit(system) {
      {
        final Source<String, BoxedUnit> words = Source.from(Arrays.asList("hello", "world", "and", "hello", "akka"));

        //#reduce-by-key-general2
        final int MAXIMUM_DISTINCT_WORDS = 1000;

        Source<Pair<String, Integer>, BoxedUnit> counts = words.via(reduceByKey(
          MAXIMUM_DISTINCT_WORDS,
          word -> word,
          key -> 0,
          (count, elem) -> count + 1,
          mat));

        //#reduce-by-key-general2
        final Future<List<Pair<String, Integer>>> f = counts.grouped(10).runWith(Sink.head(), mat);
        final Set<Pair<String, Integer>> result = Await.result(f, getRemainingTime()).stream().collect(Collectors.toSet());
        final Set<Pair<String, Integer>> expected = new HashSet<>();
        expected.add(new Pair<>("hello", 2));
        expected.add(new Pair<>("world", 1));
        expected.add(new Pair<>("and", 1));
        expected.add(new Pair<>("akka", 1));
        Assert.assertEquals(expected, result);
      }
    };
  }

}
