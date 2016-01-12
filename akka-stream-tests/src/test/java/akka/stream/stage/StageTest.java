/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.stage;

import akka.stream.StreamTest;
import akka.stream.javadsl.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.AkkaSpec;

import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.Arrays;
import java.util.List;

public class StageTest extends StreamTest {
  public StageTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
    AkkaSpec.testConf());

  @Test
  public void javaStageUsage() throws Exception {
    final java.lang.Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5);
    final Source<Integer, ?> ints = Source.from(input);
    final JavaIdentityStage<Integer> identity = new JavaIdentityStage<Integer>();

    final Future<List<Integer>> result =
      ints
        .via(identity)
        .via(identity)
        .grouped(1000)
        .runWith(Sink.<List<Integer>>head(), materializer);

    assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5), Await.result(result, Duration.create(3, "seconds")));
  }
}
