/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.japi.Function;
import akka.stream.javadsl.japi.Function2;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Future;

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

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        final Source<String> words = Source.from(Arrays.asList("hello", "world", "and", "hello", "akka"));

        //#word-count
        final int MAXIMUM_DISTINCT_WORDS = 1000;

        // split the words into separate streams first
        final Source<Pair<String, Source<String>>> wordStreams = words.groupBy(i -> i);

        // add counting logic to the streams
        Source<Future<Pair<String, Integer>>> countedWords = wordStreams.map(pair -> {
          final String word = pair.first();
          final Source<String> wordStream = pair.second();
          return wordStream.runFold(new Pair<>(word, 0), (acc, w) -> new Pair<>(word, acc.second() + 1), mat);
        });

        // get a stream of word counts
        final Source<Pair<String, Integer>> counts =
          countedWords
            .buffer(MAXIMUM_DISTINCT_WORDS, OverflowStrategy.fail())
            .mapAsync(i -> i);
        //#word-count

        counts.runWith(Sink.ignore(), mat);
      }
    };
  }

  //#reduce-by-key-general
  static public <In, K, Out> Flow<In, Pair<K, Out>> reduceByKey(
    int maximumGroupSize,
    Function<In, K> groupKey,
    Function<K, Out> foldZero,
    Function2<Out, In, Out> fold,
    FlowMaterializer mat) {

    Flow<In, Pair<K, Source<In>>> groupStreams = Flow.<In>create().groupBy(groupKey);

    Flow<In, Future<Pair<K, Out>>> reducedValues = groupStreams.map(pair -> {
      K key = pair.first();
      Source<In> groupStream = pair.second();

      return groupStream.runFold(new Pair<>(key, foldZero.apply(key)), (acc, elem) -> {
        Out aggregated = acc.second();
        return new Pair<>(key, fold.apply(aggregated, elem));
      }, mat);
    });

    return reducedValues.buffer(maximumGroupSize, OverflowStrategy.fail()).mapAsync(i -> i);
  }
  //#reduce-by-key-general

  @Test
  public void workGeneralised() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());


      {
        final Source<String> words = Source.from(Arrays.asList("hello", "world", "and", "hello", "akka"));

        //#reduce-by-key-general
        final int MAXIMUM_DISTINCT_WORDS = 1000;

        Source<Pair<String, Integer>> counts = words.via(reduceByKey(
          MAXIMUM_DISTINCT_WORDS,
          word -> word, // TODO
          key -> 0,
          (count, elem) -> count + 1,
          mat));

        //#reduce-by-key-general
        counts.runWith(Sink.ignore(), mat);
      }
    };
  }

}
