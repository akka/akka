/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.DoubleStream;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.japi.tuple.Tuple3;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import akka.testkit.JavaTestKit;
import scala.collection.Iterator;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import scala.util.Random;

public class RateTransformationDocTest {

  private static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RateTransformationDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  final Random r = new Random();

  @Test
  public void conflateShouldSummarize() throws Exception {
    //#conflate-summarize
    final Flow<Double, Tuple3<Double, Double, Integer>, BoxedUnit> statsFlow =
      Flow.of(Double.class)
        .conflate(elem -> {
          return new ArrayList<Double>() {
            {
              this.add(elem);
            }
          };
        } , (acc, elem) -> {
          acc.add(elem);
          return acc;
        })
        .map(s -> {
          final Double mean = s.stream().mapToDouble(d -> d).sum() / s.size();
          final DoubleStream se = s.stream().mapToDouble(x -> Math.pow(x - mean, 2));
          final Double stdDev = Math.sqrt(se.sum() / s.size());
          return new Tuple3(stdDev, mean, s.size());
        });
    //#conflate-summarize

    final Future<List<Tuple3<Double, Double, Integer>>> fut = Source.repeat(0).map(i -> r.nextGaussian())
      .via(statsFlow)
      .grouped(10)
      .runWith(Sink.head(), mat);

    final Duration timeout = Duration.create(100, TimeUnit.MILLISECONDS);
    Await.result(fut, timeout);
  }

  @Test
  public void conflateShouldSample() throws Exception {
    //#conflate-sample
    final Double p = 0.01;
    final Flow<Double, Double, BoxedUnit> sampleFlow = Flow.of(Double.class)
      .conflate(elem -> {
        return new ArrayList<Double>() {
          {
            this.add(elem);
          }
        };
      } , (acc, elem) -> {
        if (r.nextDouble() < p) {
          acc.add(elem);
        }
        return acc;
      })
      .mapConcat(d -> d);
    //#conflate-sample

    final Future<Double> fut = Source.from(new ArrayList<Double>(Collections.nCopies(10000, 1.0)))
      .via(sampleFlow)
      .runWith(Sink.fold(0.0, (agg, next) -> agg + next), mat);

    final Duration timeout = Duration.create(1, TimeUnit.SECONDS);
    final Double count = Await.result(fut, timeout);
  }

  @Test
  public void expandShouldRepeatLast() throws Exception {
    //#expand-last
    final Flow<Double, Double, BoxedUnit> lastFlow = Flow.of(Double.class)
      .expand(d -> d, s -> new Pair(s, s));
    //#expand-last

    final Pair<TestPublisher.Probe<Double>, Future<List<Double>>> probeFut = TestSource.<Double> probe(system)
      .via(lastFlow)
      .grouped(10)
      .toMat(Sink.head(), Keep.both())
      .run(mat);

    final TestPublisher.Probe<Double> probe = probeFut.first();
    final Future<List<Double>> fut = probeFut.second();
    probe.sendNext(1.0);
    final Duration timeout = Duration.create(1, TimeUnit.SECONDS);
    final List<Double> expanded = Await.result(fut, timeout);
    assertEquals(expanded.size(), 10);
    assertEquals(expanded.stream().mapToDouble(d -> d).sum(), 10, 0.1);
  }

  @Test
  public void expandShouldTrackDrift() throws Exception {
    //#expand-drift
    final Flow<Double, Pair<Double, Integer>, BoxedUnit> driftFlow = Flow.of(Double.class)
      .expand(d -> new Pair<Double, Integer>(d, 0), t -> {
        return new Pair(t, new Pair(t.first(), t.second() + 1));
      });
    //#expand-drift

    final Pair<TestPublisher.Probe<Double>, TestSubscriber.Probe<Pair<Double, Integer>>> pubSub = TestSource.<Double> probe(system)
      .via(driftFlow)
      .toMat(TestSink.<Pair<Double, Integer>> probe(system), Keep.both())
      .run(mat);

    final TestPublisher.Probe<Double> pub = pubSub.first();
    final TestSubscriber.Probe<Pair<Double, Integer>> sub = pubSub.second();

    sub.request(1);
    pub.sendNext(1.0);
    sub.expectNext(new Pair(1.0, 0));

    sub.requestNext(new Pair(1.0, 1));
    sub.requestNext(new Pair(1.0, 2));

    pub.sendNext(2.0);
    sub.requestNext(new Pair(2.0, 0));
  }

}
