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
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Future;
import scala.runtime.BoxedUnit;

import java.util.Arrays;

public class RecipeReduceByKey extends RecipeTest {
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

        // split the words into separate streams first
        final Source<Pair<String, Source<String, BoxedUnit>>, BoxedUnit> wordStreams = words
          .groupBy(i -> i);

        // add counting logic to the streams
        Source<Future<Pair<String, Integer>>, BoxedUnit> countedWords = wordStreams.map(pair -> {
          final String word = pair.first();
          final Source<String, BoxedUnit> wordStream = pair.second();
          return wordStream.runFold(
            new Pair<>(word, 0),
            (acc, w) -> new Pair<>(word, acc.second() + 1), mat);
        });

        // get a stream of word counts
        final Source<Pair<String, Integer>, BoxedUnit> counts = countedWords
          .buffer(MAXIMUM_DISTINCT_WORDS, OverflowStrategy.fail())
          .mapAsync(4, i -> i);
        //#word-count

        counts.runWith(Sink.ignore(), mat);
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

    Flow<In, Pair<K, Source<In, BoxedUnit>>, BoxedUnit> groupStreams = Flow.<In> create()
      .groupBy(groupKey);

    Flow<In, Future<Pair<K, Out>>, BoxedUnit> reducedValues = groupStreams.map(pair -> {
      K key = pair.first();
      Source<In, BoxedUnit> groupStream = pair.second();

      return groupStream.runFold(new Pair<>(key, foldZero.apply(key)), (acc, elem) -> {
        Out aggregated = acc.second();
        return new Pair<>(key, fold.apply(aggregated, elem));
      } , mat);
    });

    return reducedValues.buffer(maximumGroupSize, OverflowStrategy.fail()).mapAsync(4, i -> i);
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
          word -> word, // TODO
          key -> 0,
          (count, elem) -> count + 1,
          mat));

        //#reduce-by-key-general2
        counts.runWith(Sink.ignore(), mat);
      }
    };
  }

}
