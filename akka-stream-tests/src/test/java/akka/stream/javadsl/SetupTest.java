/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;

import akka.NotUsed;
import akka.japi.Pair;
import akka.stream.StreamTest;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class SetupTest extends StreamTest {
  public SetupTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("SetupTest", AkkaSpec.testConf());

  @Test
  public void shouldExposeMaterializerAndAttributesToSource() throws Exception {
    final Source<Pair<Boolean, Boolean>, CompletionStage<NotUsed>> source =
        Source.setup(
            (mat, attr) ->
                Source.single(Pair.create(mat.isShutdown(), attr.attributeList().isEmpty())));

    assertEquals(
        Pair.create(false, false),
        source.runWith(Sink.head(), materializer).toCompletableFuture().get(5, TimeUnit.SECONDS));
  }

  @Test
  public void shouldExposeMaterializerAndAttributesToFlow() throws Exception {
    final Flow<Object, Pair<Boolean, Boolean>, CompletionStage<NotUsed>> flow =
        Flow.setup(
            (mat, attr) ->
                Flow.fromSinkAndSource(
                    Sink.ignore(),
                    Source.single(Pair.create(mat.isShutdown(), attr.attributeList().isEmpty()))));

    assertEquals(
        Pair.create(false, false),
        Source.empty()
            .via(flow)
            .runWith(Sink.head(), materializer)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS));
  }

  @Test
  public void shouldExposeMaterializerAndAttributesToSink() throws Exception {
    Sink<Object, CompletionStage<CompletionStage<Pair<Boolean, Boolean>>>> sink =
        Sink.setup(
            (mat, attr) ->
                Sink.fold(
                    Pair.create(mat.isShutdown(), attr.attributeList().isEmpty()), Keep.left()));

    assertEquals(
        Pair.create(false, false),
        Source.empty()
            .runWith(sink, materializer)
            .thenCompose(c -> c)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS));
  }
}
