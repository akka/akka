/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.io;

import akka.stream.IOOperationIncompleteException;
import akka.stream.IOResult;
import akka.stream.StreamTest;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.StreamConverters;
import akka.stream.testkit.Utils;
import akka.util.ByteString;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.matchers.JUnitMatchers;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.io.OutputStream;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class OutputStreamSinkTest extends StreamTest {
  public OutputStreamSinkTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("OutputStreamSinkTest", Utils.UnboundedMailboxConfig());

  @Test
  public void mustSignalFailureViaFailingFuture() throws Exception {

    final OutputStream os =
        new OutputStream() {
          volatile int left = 3;

          public void write(int data) {
            if (left == 0) {
              throw new RuntimeException("Can't accept more data.");
            }
            left -= 1;
          }
        };
    final CompletionStage<IOResult> resultFuture =
        Source.single(ByteString.fromString("123456"))
            .runWith(StreamConverters.fromOutputStream(() -> os), system);
    try {
      resultFuture.toCompletableFuture().get(3, TimeUnit.SECONDS);
      Assert.fail("expected IOIncompleteException");
    } catch (ExecutionException e) {
      Assert.assertEquals(e.getCause().getClass(), IOOperationIncompleteException.class);
      Assert.assertEquals(e.getCause().getCause().getMessage(), "Can't accept more data.");
    }
  }
}
