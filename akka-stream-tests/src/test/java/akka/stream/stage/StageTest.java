/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.stage;

import akka.NotUsed;
import akka.stream.StreamTest;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.AkkaSpec;

import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class StageTest extends StreamTest {
  public StageTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("FlowTest", AkkaSpec.testConf());

  @Test
  public void javaStageUsage() throws Exception {
    final java.lang.Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5);
    final Source<Integer, NotUsed> ints = Source.from(input);
    final JavaIdentityStage<Integer> identity = new JavaIdentityStage<Integer>();

    final CompletionStage<List<Integer>> result =
        ints.via(identity)
            .via(identity)
            .grouped(1000)
            .runWith(Sink.<List<Integer>>head(), materializer);

    assertEquals(
        Arrays.asList(0, 1, 2, 3, 4, 5), result.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }
}
