/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;

import java.util.Arrays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import akka.NotUsed;
import akka.japi.Pair;
import akka.japi.function.Function;
import akka.stream.*;
import akka.testkit.javadsl.TestKit;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import akka.testkit.AkkaSpec;
import akka.testkit.AkkaJUnitActorSystemResource;

import static org.junit.Assert.*;

public class SinkTest extends StreamTest {
  public SinkTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  @Test
  public void mustBeAbleToUseFanoutPublisher() throws Exception {
    final Sink<Object, Publisher<Object>> pubSink = Sink.asPublisher(AsPublisher.WITH_FANOUT);
    @SuppressWarnings("unused")
    final Publisher<Object> publisher = Source.from(new ArrayList<Object>()).runWith(pubSink, materializer);
  }

  @Test
  public void mustBeAbleToUseFuture() throws Exception {
    final Sink<Integer, CompletionStage<Integer>> futSink = Sink.head();
    final List<Integer> list = Collections.singletonList(1);
    final CompletionStage<Integer> future = Source.from(list).runWith(futSink, materializer);
    assert future.toCompletableFuture().get(1, TimeUnit.SECONDS).equals(1);
  }

  @Test
  public void mustBeAbleToUseFold() throws Exception {
    Sink<Integer, CompletionStage<Integer>> foldSink = Sink.fold(0, (arg1, arg2) -> arg1 + arg2);
    @SuppressWarnings("unused")
    CompletionStage<Integer> integerFuture = Source.from(new ArrayList<Integer>()).runWith(foldSink, materializer);
  }

  @Test
  public void mustBeAbleToUseActorRefSink() throws Exception {
    final TestKit probe = new TestKit(system);
    final Sink<Integer, ?> actorRefSink = Sink.actorRef(probe.getRef(), "done");
    Source.from(Arrays.asList(1, 2, 3)).runWith(actorRefSink, materializer);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals("done");
  }

  @Test
  public void mustBeAbleToUseCollector() throws Exception {
    final List<Integer> list = Arrays.asList(1, 2, 3);
    final Sink<Integer, CompletionStage<List<Integer>>> collectorSink = StreamConverters.javaCollector(Collectors::toList);
    CompletionStage<List<Integer>> result = Source.from(list).runWith(collectorSink, materializer);
    assertEquals(list, result.toCompletableFuture().get(1, TimeUnit.SECONDS));
  }

  @Test
  public void mustBeAbleToCombine() throws Exception {
    final TestKit probe1 = new TestKit(system);
    final TestKit probe2 = new TestKit(system);

    final Sink<Integer, ?> sink1 = Sink.actorRef(probe1.getRef(), "done1");
    final Sink<Integer, ?> sink2 = Sink.actorRef(probe2.getRef(), "done2");

    final Sink<Integer, ?> sink = Sink.combine(sink1, sink2, new ArrayList<Sink<Integer, ?>>(),
            new Function<Integer, Graph<UniformFanOutShape<Integer, Integer>, NotUsed>>() {
              public Graph<UniformFanOutShape<Integer, Integer>, NotUsed> apply(Integer elem) {
                return Broadcast.create(elem);
              }
            }
    );

    Source.from(Arrays.asList(0, 1)).runWith(sink, materializer);

    probe1.expectMsgEquals(0);
    probe2.expectMsgEquals(0);
    probe1.expectMsgEquals(1);
    probe2.expectMsgEquals(1);

    probe1.expectMsgEquals("done1");
    probe2.expectMsgEquals("done2");
  }

  @Test
  public void mustBeAbleToUseContramap() throws Exception {
    List<Integer> out = Source.range(0, 2).toMat(Sink.<Integer>seq().contramap(x -> x + 1), Keep.right())
      .run(materializer).toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertEquals(Arrays.asList(1, 2, 3), out);
  }

  @Test
  public void mustBeAbleToUsePreMaterialize() throws Exception {
    Pair<CompletionStage<String>, Sink<String, NotUsed>> pair = Sink.<String>head().preMaterialize(materializer);

    CompletableFuture<String> future = pair.first().toCompletableFuture();
    assertEquals(false, future.isDone()); // not yet, only once actually source attached

    String element = "element";
    Source.single(element).runWith(pair.second(), materializer);

    String got = future.get(3, TimeUnit.SECONDS);// should complete nicely
    assertEquals(element, got);
    assertEquals(true, future.isDone());
  }

  public void mustSuitablyOverrideAttributeHandlingMethods() {
    @SuppressWarnings("unused")
    final Sink<Integer, CompletionStage<Integer>> s =
        Sink.<Integer> head().withAttributes(Attributes.name("")).addAttributes(Attributes.asyncBoundary()).named("");
  }
}
