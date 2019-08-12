/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;

import akka.NotUsed;
import akka.japi.JavaPartialFunction;
import akka.japi.Option;
import akka.japi.Pair;
import akka.stream.StreamTest;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import org.junit.ClassRule;
import org.junit.Test;
import scala.util.Success;
import scala.util.Try;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class RetryFlowTest extends StreamTest {
  public RetryFlowTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("RetryFlowTest", AkkaSpec.testConf());

  @Test
  public void beAbleToUseRetryFlow() {
    Flow<Pair<Integer, NotUsed>, Pair<Try<Integer>, NotUsed>, NotUsed> flow =
        Flow.fromFunction(
            in -> {
              final Integer request = in.first();
              return Pair.create(Success.apply(request / 2), NotUsed.getInstance());
            });

    final JavaPartialFunction<
            Pair<Try<Integer>, NotUsed>, Option<Collection<Pair<Integer, NotUsed>>>>
        retryWith =
            new JavaPartialFunction<
                Pair<Try<Integer>, NotUsed>, Option<Collection<Pair<Integer, NotUsed>>>>() {
              public akka.japi.Option<Collection<Pair<Integer, NotUsed>>> apply(
                  Pair<Try<Integer>, NotUsed> in, boolean isCheck) {
                final Try<Integer> response = in.first();
                if (response.isSuccess()) {
                  final Integer result = response.get();
                  if (result > 0) {
                    return akka.japi.Option.some(
                        Collections.singleton(Pair.create(result, NotUsed.getInstance())));
                  } else {
                    return akka.japi.Option.none();
                  }
                } else {
                  return akka.japi.Option.none();
                }
              }
            };

    Flow<akka.japi.Pair<Integer, NotUsed>, akka.japi.Pair<Try<Integer>, NotUsed>, NotUsed>
        retryFlow =
            RetryFlow.withBackoff(
                8, Duration.ofMillis(10), Duration.ofSeconds(5), 0, flow, retryWith);

    Pair<TestPublisher.Probe<Integer>, TestSubscriber.Probe<Pair<Try<Integer>, NotUsed>>> probes =
        TestSource.<Integer>probe(system)
            .map(i -> Pair.create(i, NotUsed.getInstance()))
            .via(retryFlow)
            .toMat(TestSink.probe(system), Keep.both())
            .run(materializer);

    final TestPublisher.Probe<Integer> source = probes.first();
    final TestSubscriber.Probe<Pair<Try<Integer>, NotUsed>> sink = probes.second();

    sink.request(4);

    source.sendNext(8);
    assertEquals(4, sink.expectNext().first().get().intValue());
    assertEquals(2, sink.expectNext().first().get().intValue());
    assertEquals(1, sink.expectNext().first().get().intValue());
    assertEquals(0, sink.expectNext().first().get().intValue());

    source.sendComplete();
    sink.expectComplete();
  }
}
