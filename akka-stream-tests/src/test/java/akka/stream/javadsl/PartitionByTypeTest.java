/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;
/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.NotUsed;
import akka.stream.StreamTest;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class PartitionByTypeTest extends StreamTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  public PartitionByTypeTest() {
    super(actorSystemResource);
  }


  interface MyResult {}
  static final class MyError implements MyResult {
    public final Throwable error;
    public MyError(Throwable error) {
      this.error = error;
    }
  }
  static final class MySuccess implements MyResult {
    public final String value;
    public MySuccess(String value) {
      this.value = value;
    }
  }

  @Test
  public void partitionByType() throws Exception {
    Source<MyResult, NotUsed> results = Source.from(Arrays.asList(
        new MySuccess("one"),
        new MySuccess("two"),
        new MyError(new RuntimeException("boom")),
        new MySuccess("three")));

    Sink<MyError, CompletionStage<List<MyError>>> errorSink = Sink.seq();
    Sink<MySuccess, CompletionStage<List<MySuccess>>> successSink = Sink.seq();

    Sink<MyResult, List<Object>> partitionSink =
      PartitionByType.create(MyResult.class)
          .addSink(MyError.class, errorSink)
          .addSink(MySuccess.class, successSink)
          .build();


    List<Object> materializedValues = results.runWith(partitionSink, materializer);

    // materialized values in the order the sinks were added
    @SuppressWarnings("unchecked")
    List<MyError> errors = ((CompletionStage<List<MyError>>) materializedValues.get(0))
        .toCompletableFuture().get(3, TimeUnit.SECONDS);
    @SuppressWarnings("unchecked")
    List<MySuccess> successes = ((CompletionStage<List<MySuccess>>) materializedValues.get(1))
        .toCompletableFuture().get(3, TimeUnit.SECONDS);

    assertEquals(3, successes.size());
    assertEquals(1, errors.size());
  }

}
