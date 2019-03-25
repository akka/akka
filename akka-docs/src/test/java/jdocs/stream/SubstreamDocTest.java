/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.javadsl.TestKit;
import jdocs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SubstreamDocTest extends AbstractJavaTest {

  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlowDocTest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  @Test
  public void demonstrateGroupBy() throws Exception {
    // #groupBy1
    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)).groupBy(3, elem -> elem % 3);
    // #groupBy1

    // #groupBy2
    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        .groupBy(3, elem -> elem % 3)
        .to(Sink.ignore())
        .run(mat);
    // #groupBy2

    // #groupBy3
    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        .groupBy(3, elem -> elem % 3)
        .mergeSubstreams()
        .runWith(Sink.ignore(), mat);
    // #groupBy3

    // #groupBy4
    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        .groupBy(3, elem -> elem % 3)
        .mergeSubstreamsWithParallelism(2)
        .runWith(Sink.ignore(), mat);
    // concatSubstreams is equivalent to mergeSubstreamsWithParallelism(1)
    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        .groupBy(3, elem -> elem % 3)
        .concatSubstreams()
        .runWith(Sink.ignore(), mat);
    // #groupBy4
  }

  @Test
  public void demonstrateSplitWhenAfter() throws Exception {
    // #splitWhenAfter
    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)).splitWhen(elem -> elem == 3);

    Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)).splitAfter(elem -> elem == 3);
    // #splitWhenAfter

    // #wordCount
    String text =
        "This is the first line.\n" + "The second line.\n" + "There is also the 3rd line\n";

    Source.from(Arrays.asList(text.split("")))
        .map(x -> x.charAt(0))
        .splitAfter(x -> x == '\n')
        .filter(x -> x != '\n')
        .map(x -> 1)
        .reduce((x, y) -> x + y)
        .to(Sink.foreach(x -> System.out.println(x)))
        .run(mat);
    // #wordCount
    Thread.sleep(1000);
  }

  @Test
  public void demonstrateflatMapConcatMerge() throws Exception {
    // #flatMapConcat
    Source.from(Arrays.asList(1, 2))
        .flatMapConcat(i -> Source.from(Arrays.asList(i, i, i)))
        .runWith(Sink.ignore(), mat);
    // #flatMapConcat

    // #flatMapMerge
    Source.from(Arrays.asList(1, 2))
        .flatMapMerge(2, i -> Source.from(Arrays.asList(i, i, i)))
        .runWith(Sink.ignore(), mat);
    // #flatMapMerge
  }
}
