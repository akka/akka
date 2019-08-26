/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;

import akka.NotUsed;
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
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

import static akka.NotUsed.notUsed;
import static org.junit.Assert.assertEquals;

public class RetryFlowTest extends StreamTest {
  public RetryFlowTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("RetryFlowTest", AkkaSpec.testConf());

  @Test
  public void retrySuccessfulResponses() {
    final Integer parallelism = 8;
    final Duration minBackoff = Duration.ofMillis(10);
    final Duration maxBackoff = Duration.ofSeconds(5);
    final Integer randomFactor = 0;
    final Flow<Pair<Integer, NotUsed>, Pair<Try<Integer>, NotUsed>, NotUsed> flow =
        Flow.fromFunction(
            in -> {
              final Integer request = in.first();
              return Pair.create(Success.apply(request / 2), notUsed());
            });

    final Flow<Pair<Integer, NotUsed>, Pair<Try<Integer>, NotUsed>, NotUsed> retryFlow =
        RetryFlow.withBackoff(
            parallelism,
            minBackoff,
            maxBackoff,
            randomFactor,
            flow,
            r -> {
              final Integer result = r.first();
              if (result > 0) {
                return Optional.of(Collections.singleton(Pair.create(result, notUsed())));
              } else {
                return Optional.empty();
              }
            },
            r -> Optional.empty());

    final Pair<TestPublisher.Probe<Integer>, TestSubscriber.Probe<Pair<Try<Integer>, NotUsed>>>
        probes =
            TestSource.<Integer>probe(system)
                .map(i -> Pair.create(i, notUsed()))
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

  @Test
  public void retryFailedResponses() {
    final Integer parallelism = 8;
    final Duration minBackoff = Duration.ofMillis(10);
    final Duration maxBackoff = Duration.ofSeconds(5);
    final Integer randomFactor = 0;
    final Flow<Pair<Integer, Integer>, Pair<Try<Integer>, Integer>, NotUsed> flow =
        Flow.fromFunction(
            in -> {
              final Integer request = in.first();
              if (request > 0)
                return Pair.create(Failure.apply(new Error("Failed response")), request);
              else return Pair.create(Success.apply(request), request);
            });

    final Flow<Pair<Integer, Integer>, Pair<Try<Integer>, Integer>, NotUsed> retryFlow =
        RetryFlow.withBackoff(
            parallelism,
            minBackoff,
            maxBackoff,
            randomFactor,
            flow,
            r -> Optional.empty(),
            r -> {
              final Integer state = r.second();
              if (state > 0) {
                return Optional.of(Collections.singleton(Pair.create(state / 2, state / 2)));
              } else {
                return Optional.empty();
              }
            });

    final Pair<TestPublisher.Probe<Integer>, TestSubscriber.Probe<Pair<Try<Integer>, Integer>>>
        probes =
            TestSource.<Integer>probe(system)
                .map(i -> Pair.create(i, i))
                .via(retryFlow)
                .toMat(TestSink.probe(system), Keep.both())
                .run(materializer);

    final TestPublisher.Probe<Integer> source = probes.first();
    final TestSubscriber.Probe<Pair<Try<Integer>, Integer>> sink = probes.second();

    sink.request(1);
    source.sendNext(8);

    Pair<Try<Integer>, Integer> response = sink.expectNext();
    assertEquals(0, response.first().get().intValue());
    assertEquals(4, response.second().intValue());

    source.sendComplete();
    sink.expectComplete();
  }
}
