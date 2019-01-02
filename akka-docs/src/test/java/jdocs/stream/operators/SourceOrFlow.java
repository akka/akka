/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators;

import akka.stream.Materializer;
import akka.stream.javadsl.Flow;

import akka.NotUsed;
import akka.japi.function.Function2;

//#zip
//#zip-with
//#zip-with-index
//#or-else
//#prepend
//#concat
//#interleave
//#merge
//#merge-sorted
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Sink;
import java.util.Arrays;

//#merge-sorted
//#merge
//#interleave
//#concat
//#prepend
//#or-else
//#zip-with-index
//#zip-with
//#zip

//#log
import akka.stream.Attributes;
import akka.stream.javadsl.Source;
//#log

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;


class SourceOrFlow {
  private static Materializer materializer = null;

  void logExample() {
    Flow.of(String.class)
        //#log
        .log("myStream")
        .addAttributes(Attributes.createLogLevels(
            Attributes.logLevelOff(), // onElement
            Attributes.logLevelError(), // onFailure
            Attributes.logLevelInfo())) // onFinish
    //#log
    ;
  }

  void zipWithIndexExample() {
    Materializer materializer = null;
    //#zip-with-index
    Source.from(Arrays.asList("apple", "orange", "banana"))
            .zipWithIndex()
            .runWith(Sink.foreach(System.out::print), materializer);
    // this will print ('apple', 0), ('orange', 1), ('banana', 2)
    //#zip-with-index
  }
  
  void zipExample() {
    //#zip
    Source<String, NotUsed> sourceFruits = Source.from(Arrays.asList("apple", "orange", "banana"));
    Source<String, NotUsed> sourceFirstLetters = Source.from(Arrays.asList("A", "O", "B"));
    sourceFruits.zip(sourceFirstLetters).runWith(Sink.foreach(System.out::print), materializer);
    // this will print ('apple', 'A'), ('orange', 'O'), ('banana', 'B')

    //#zip
  }

  void zipWithExample() {
    //#zip-with
    Source<String, NotUsed> sourceCount = Source.from(Arrays.asList("one", "two", "three"));
    Source<String, NotUsed> sourceFruits = Source.from(Arrays.asList("apple", "orange", "banana"));
    sourceCount.zipWith(
            sourceFruits,
            (Function2<String, String, String>) (countStr, fruitName) -> countStr + " " + fruitName
    ).runWith(Sink.foreach(System.out::print), materializer);
    // this will print 'one apple', 'two orange', 'three banana'

    //#zip-with
  }

  void prependExample() {
      //#prepend
      Source<String, NotUsed> ladies = Source.from(Arrays.asList("Emma", "Emily"));
      Source<String, NotUsed> gentlemen = Source.from(Arrays.asList("Liam", "William"));
      gentlemen.prepend(ladies).runWith(Sink.foreach(System.out::print), materializer);
      // this will print "Emma", "Emily", "Liam", "William"

      //#prepend
  }


  void concatExample() {
      //#concat
      Source<Integer, NotUsed> sourceA = Source.from(Arrays.asList(1, 2, 3, 4));
      Source<Integer, NotUsed> sourceB = Source.from(Arrays.asList(10, 20, 30, 40));
      sourceA.concat(sourceB).runWith(Sink.foreach(System.out::print), materializer);
      //prints 1, 2, 3, 4, 10, 20, 30, 40

      //#concat
  }


  void interleaveExample() {
      //#interleave
      Source<Integer, NotUsed> sourceA = Source.from(Arrays.asList(1, 2, 3, 4));
      Source<Integer, NotUsed> sourceB = Source.from(Arrays.asList(10, 20, 30, 40));
      sourceA.interleave(sourceB, 2).runWith(Sink.foreach(System.out::print), materializer);
      //prints 1, 2, 10, 20, 3, 4, 30, 40

      //#interleave
  }


  void mergeExample() {
      //#merge
      Source<Integer, NotUsed> sourceA = Source.from(Arrays.asList(1, 2, 3, 4));
      Source<Integer, NotUsed> sourceB = Source.from(Arrays.asList(10, 20, 30, 40));
      sourceA.merge(sourceB).runWith(Sink.foreach(System.out::print), materializer);
      // merging is not deterministic, can for example print 1, 2, 3, 4, 10, 20, 30, 40

      //#merge
  }


  void mergeSortedExample() {
      //#merge-sorted
      Source<Integer, NotUsed> sourceA = Source.from(Arrays.asList(1, 3, 5, 7));
      Source<Integer, NotUsed> sourceB = Source.from(Arrays.asList(2, 4, 6, 8));
      sourceA.mergeSorted(sourceB, Comparator.<Integer>naturalOrder()).runWith(Sink.foreach(System.out::print), materializer);
      //prints 1, 2, 3, 4, 5, 6, 7, 8

      Source<Integer, NotUsed> sourceC = Source.from(Arrays.asList(20, 1, 1, 1));
      sourceA.mergeSorted(sourceC, Comparator.<Integer>naturalOrder()).runWith(Sink.foreach(System.out::print), materializer);
      //prints 1, 3, 5, 7, 20, 1, 1, 1
      //#merge-sorted
  }

  void orElseExample() {
    //#or-else
      Source<String, NotUsed> source1 = Source.from(Arrays.asList("First source"));
      Source<String, NotUsed> source2 = Source.from(Arrays.asList("Second source"));
      Source<String, NotUsed> emptySource = Source.empty();

      source1.orElse(source2).runWith(Sink.foreach(System.out::print), materializer);
      // this will print "First source"

      emptySource.orElse(source2).runWith(Sink.foreach(System.out::print), materializer);
      // this will print "Second source"

    //#or-else
  }

  void conflateExample() {
    //#conflate
    Source.cycle(() -> Arrays.asList(1, 10, 100).iterator())
        .throttle(10, Duration.ofSeconds(1)) // fast upstream
        .conflate((Integer acc, Integer el) -> acc + el)
        .throttle(1, Duration.ofSeconds(1)); // slow downstream
    //#conflate
  }

  static //#conflateWithSeed-type
  class Summed {

    private final Integer el;

    public Summed(Integer el) {
      this.el = el;
    }

    public Summed sum(Summed other) {
      return new Summed(this.el + other.el);
    }
  }
  //#conflateWithSeed-type

  void conflateWithSeedExample() {
    //#conflateWithSeed

    Source.cycle(() -> Arrays.asList(1, 10, 100).iterator())
        .throttle(10, Duration.ofSeconds(1)) // fast upstream
        .conflateWithSeed(Summed::new, (Summed acc, Integer el) -> acc.sum(new Summed(el)))
        .throttle(1, Duration.ofSeconds(1)); // slow downstream
    //#conflateWithSeed
  }
}
