/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.japi.pf.PFBuilder;
import akka.stream.javadsl.Flow;

import akka.NotUsed;
import akka.japi.function.Function2;

// #zip
// #zip-with
// #zip-with-index
// #or-else
// #prepend
// #concat
// #interleave
// #merge
// #merge-sorted
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Sink;
import java.util.Arrays;

// #merge-sorted
// #merge
// #interleave
// #concat
// #prepend
// #or-else
// #zip-with-index
// #zip-with
// #zip

// #log
import akka.event.LogMarker;
import akka.stream.Attributes;

// #log

import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

class SourceOrFlow {
  private static ActorSystem system = null;

  void logExample() {
    Flow.of(String.class)
        // #log
        .log("myStream")
        .addAttributes(
            Attributes.createLogLevels(
                Attributes.logLevelOff(), // onElement
                Attributes.logLevelInfo(), // onFinish
                Attributes.logLevelError())) // onFailure
    // #log
    ;
  }

  void logWithMarkerExample() {
    Flow.of(String.class)
        // #logWithMarker
        .logWithMarker(
            "myStream", (e) -> LogMarker.create("myMarker", Collections.singletonMap("element", e)))
        .addAttributes(
            Attributes.createLogLevels(
                Attributes.logLevelOff(), // onElement
                Attributes.logLevelInfo(), // onFinish
                Attributes.logLevelError())) // onFailure
    // #logWithMarker
    ;
  }

  void zipWithIndexExample() {
    // #zip-with-index
    Source.from(Arrays.asList("apple", "orange", "banana"))
        .zipWithIndex()
        .runWith(Sink.foreach(System.out::print), system);
    // this will print ('apple', 0), ('orange', 1), ('banana', 2)
    // #zip-with-index
  }

  void zipExample() {
    // #zip
    Source<String, NotUsed> sourceFruits = Source.from(Arrays.asList("apple", "orange", "banana"));
    Source<String, NotUsed> sourceFirstLetters = Source.from(Arrays.asList("A", "O", "B"));
    sourceFruits.zip(sourceFirstLetters).runWith(Sink.foreach(System.out::print), system);
    // this will print ('apple', 'A'), ('orange', 'O'), ('banana', 'B')

    // #zip
  }

  void zipWithExample() {
    // #zip-with
    Source<String, NotUsed> sourceCount = Source.from(Arrays.asList("one", "two", "three"));
    Source<String, NotUsed> sourceFruits = Source.from(Arrays.asList("apple", "orange", "banana"));
    sourceCount
        .zipWith(
            sourceFruits,
            (Function2<String, String, String>) (countStr, fruitName) -> countStr + " " + fruitName)
        .runWith(Sink.foreach(System.out::print), system);
    // this will print 'one apple', 'two orange', 'three banana'

    // #zip-with
  }

  void prependExample() {
    // #prepend
    Source<String, NotUsed> ladies = Source.from(Arrays.asList("Emma", "Emily"));
    Source<String, NotUsed> gentlemen = Source.from(Arrays.asList("Liam", "William"));
    gentlemen.prepend(ladies).runWith(Sink.foreach(System.out::print), system);
    // this will print "Emma", "Emily", "Liam", "William"

    // #prepend
  }

  void concatExample() {
    // #concat
    Source<Integer, NotUsed> sourceA = Source.from(Arrays.asList(1, 2, 3, 4));
    Source<Integer, NotUsed> sourceB = Source.from(Arrays.asList(10, 20, 30, 40));
    sourceA.concat(sourceB).runWith(Sink.foreach(System.out::print), system);
    // prints 1, 2, 3, 4, 10, 20, 30, 40

    // #concat
  }

  void interleaveExample() {
    // #interleave
    Source<Integer, NotUsed> sourceA = Source.from(Arrays.asList(1, 2, 3, 4));
    Source<Integer, NotUsed> sourceB = Source.from(Arrays.asList(10, 20, 30, 40));
    sourceA.interleave(sourceB, 2).runWith(Sink.foreach(System.out::print), system);
    // prints 1, 2, 10, 20, 3, 4, 30, 40

    // #interleave
  }

  void mergeExample() {
    // #merge
    Source<Integer, NotUsed> sourceA = Source.from(Arrays.asList(1, 2, 3, 4));
    Source<Integer, NotUsed> sourceB = Source.from(Arrays.asList(10, 20, 30, 40));
    sourceA.merge(sourceB).runWith(Sink.foreach(System.out::print), system);
    // merging is not deterministic, can for example print 1, 2, 3, 4, 10, 20, 30, 40

    // #merge
  }

  void mergeSortedExample() {
    // #merge-sorted
    Source<Integer, NotUsed> sourceA = Source.from(Arrays.asList(1, 3, 5, 7));
    Source<Integer, NotUsed> sourceB = Source.from(Arrays.asList(2, 4, 6, 8));
    sourceA
        .mergeSorted(sourceB, Comparator.<Integer>naturalOrder())
        .runWith(Sink.foreach(System.out::print), system);
    // prints 1, 2, 3, 4, 5, 6, 7, 8

    Source<Integer, NotUsed> sourceC = Source.from(Arrays.asList(20, 1, 1, 1));
    sourceA
        .mergeSorted(sourceC, Comparator.<Integer>naturalOrder())
        .runWith(Sink.foreach(System.out::print), system);
    // prints 1, 3, 5, 7, 20, 1, 1, 1
    // #merge-sorted
  }

  void orElseExample() {
    // #or-else
    Source<String, NotUsed> source1 = Source.from(Arrays.asList("First source"));
    Source<String, NotUsed> source2 = Source.from(Arrays.asList("Second source"));
    Source<String, NotUsed> emptySource = Source.empty();

    source1.orElse(source2).runWith(Sink.foreach(System.out::print), system);
    // this will print "First source"

    emptySource.orElse(source2).runWith(Sink.foreach(System.out::print), system);
    // this will print "Second source"

    // #or-else
  }

  void conflateExample() {
    // #conflate
    Source.cycle(() -> Arrays.asList(1, 10, 100).iterator())
        .throttle(10, Duration.ofSeconds(1)) // fast upstream
        .conflate((Integer acc, Integer el) -> acc + el)
        .throttle(1, Duration.ofSeconds(1)); // slow downstream
    // #conflate
  }

  void scanExample() {
    // #scan
    Source<Integer, NotUsed> source = Source.range(1, 5);
    source.scan(0, (acc, x) -> acc + x).runForeach(System.out::println, system);
    // 0  (= 0)
    // 1  (= 0 + 1)
    // 3  (= 0 + 1 + 2)
    // 6  (= 0 + 1 + 2 + 3)
    // 10 (= 0 + 1 + 2 + 3 + 4)
    // 15 (= 0 + 1 + 2 + 3 + 4 + 5)
    // #scan
  }

  // #scan-async
  CompletionStage<Integer> asyncFunction(int acc, int next) {
    return CompletableFuture.supplyAsync(() -> acc + next);
  }
  // #scan-async

  void scanAsyncExample() {
    // #scan-async
    Source<Integer, NotUsed> source = Source.range(1, 5);
    source.scanAsync(0, (acc, x) -> asyncFunction(acc, x)).runForeach(System.out::println, system);
    // 0  (= 0)
    // 1  (= 0 + 1)
    // 3  (= 0 + 1 + 2)
    // 6  (= 0 + 1 + 2 + 3)
    // 10 (= 0 + 1 + 2 + 3 + 4)
    // 15 (= 0 + 1 + 2 + 3 + 4 + 5)
    // #scan-async
  }

  static // #conflateWithSeed-type
  class Summed {

    private final Integer el;

    public Summed(Integer el) {
      this.el = el;
    }

    public Summed sum(Summed other) {
      return new Summed(this.el + other.el);
    }
  }
  // #conflateWithSeed-type

  void conflateWithSeedExample() {
    // #conflateWithSeed

    Source.cycle(() -> Arrays.asList(1, 10, 100).iterator())
        .throttle(10, Duration.ofSeconds(1)) // fast upstream
        .conflateWithSeed(Summed::new, (Summed acc, Integer el) -> acc.sum(new Summed(el)))
        .throttle(1, Duration.ofSeconds(1)); // slow downstream
    // #conflateWithSeed
  }

  // #collect-elements
  static interface Message {}

  static class Ping implements Message {
    final int id;

    Ping(int id) {
      this.id = id;
    }
  }

  static class Pong {
    final int id;

    Pong(int id) {
      this.id = id;
    }
  }
  // #collect-elements

  void collectExample() {
    // #collect
    Flow<Message, Pong, NotUsed> flow =
        Flow.of(Message.class)
            .collect(
                new PFBuilder<Message, Pong>()
                    .match(Ping.class, p -> p.id != 0, p -> new Pong(p.id))
                    .build());
    // #collect
  }

  void collectTypeExample() {
    // #collectType
    Flow<Message, Pong, NotUsed> flow =
        Flow.of(Message.class)
            .collectType(Ping.class)
            .filter(p -> p.id != 0)
            .map(p -> new Pong(p.id));
    // #collectType
  }

  void groupedExample() {
    // #grouped
    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7))
        .grouped(3)
        .runForeach(System.out::println, system);
    // [1, 2, 3]
    // [4, 5, 6]
    // [7]

    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7))
        .grouped(3)
        .map(g -> g.stream().reduce(0, Integer::sum))
        .runForeach(System.out::println, system);
    // 6   (= 1 + 2 + 3)
    // 15  (= 4 + 5 + 6)
    // 7   (= 7)
    // #grouped
  }

  static
  // #fold
  class Histogram {
    final long low;
    final long high;

    private Histogram(long low, long high) {
      this.low = low;
      this.high = high;
    }

    // Immutable start value
    public static Histogram INSTANCE = new Histogram(0L, 0L);

    public Histogram add(int number) {
      if (number < 100) {
        return new Histogram(low + 1L, high);
      } else {
        return new Histogram(low, high + 1L);
      }
    }
  }
  // #fold

  void foldExample() {
    // #fold

    // Folding over the numbers from 1 to 150:
    Source.range(1, 150)
        .fold(Histogram.INSTANCE, (acc, n) -> acc.add(n))
        .runForeach(h -> System.out.println("Histogram(" + h.low + ", " + h.high + ")"), system);

    // Prints: Histogram(99, 51)
    // #fold
  }

  void takeExample() {
    // #take
    Source.from(Arrays.asList(1, 2, 3, 4, 5)).take(3).runForeach(System.out::println, system);
    // this will print:
    // 1
    // 2
    // 3
    // #take
  }

  void takeWhileExample() {
    // #take-while
    Source.from(Arrays.asList(1, 2, 3, 4, 5))
        .takeWhile(i -> i < 3)
        .runForeach(System.out::println, system);
    // this will print:
    // 1
    // 2
    // #take-while
  }

  void filterExample() {
    // #filter
    Source<String, NotUsed> words =
        Source.from(
            Arrays.asList(
                ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt "
                        + "ut labore et dolore magna aliqua")
                    .split(" ")));

    Source<String, NotUsed> longWords = words.filter(w -> w.length() > 6);

    longWords.runWith(Sink.foreach(System.out::print), system);
    // consectetur
    // adipiscing
    // eiusmod
    // incididunt
    // #filter
  }

  void filterNotExample() {
    // #filterNot
    Source<String, NotUsed> words =
        Source.from(
            Arrays.asList(
                ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt "
                        + "ut labore et dolore magna aliqua")
                    .split(" ")));

    Source<String, NotUsed> longWords = words.filterNot(w -> w.length() <= 6);

    longWords.runWith(Sink.foreach(System.out::print), system);
    // consectetur
    // adipiscing
    // eiusmod
    // incididunt
    // #filterNot
  }

  void dropExample() {
    // #drop
    Source<Integer, NotUsed> fiveIntegers = Source.from(Arrays.asList(1, 2, 3, 4, 5));
    Source<Integer, NotUsed> droppedThreeInts = fiveIntegers.drop(3);

    droppedThreeInts.runWith(Sink.foreach(System.out::print), system);
    // 4
    // 5
    // #drop
  }

  void dropWhileExample() {
    // #dropWhile
    Source<Integer, NotUsed> droppedWhileNegative =
        Source.from(Arrays.asList(-3, -2, -1, 0, 1, 2, 3, -1)).dropWhile(integer -> integer < 0);

    droppedWhileNegative.runWith(Sink.foreach(System.out::print), system);
    // 1
    // 2
    // 3
    // -1
    // #dropWhile
  }

  void watchExample() {
    // #watch
    final ActorRef ref = someActor();
    Flow<String, String, NotUsed> flow =
        Flow.of(String.class)
            .watch(ref)
            .recover(akka.stream.WatchedActorTerminatedException.class, () -> ref + " terminated");
    // #watch
  }

  private ActorRef someActor() {
    return null;
  }
}
