/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.io;

import akka.stream.IOResult;
import akka.stream.StreamTest;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.StreamConverters;
import akka.stream.testkit.Utils;
import akka.util.ByteString;
import org.junit.ClassRule;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.io.OutputStream;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class OutputStreamSinkTest  extends StreamTest {
    public OutputStreamSinkTest() {
        super(actorSystemResource);
    }

    @ClassRule
    public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("OutputStreamSinkTest",
            Utils.UnboundedMailboxConfig());
    @Test
    public void mustSignalFailureViaIoResult() throws Exception {

      final OutputStream os = new OutputStream() {
        volatile int left = 3;
        public void write(int data) {
          if (left == 0) {
            throw new RuntimeException("Can't accept more data.");
          }
          left -= 1;
        }
      };
      final CompletionStage<IOResult> resultFuture = Source.single(ByteString.fromString("123456")).runWith(StreamConverters.fromOutputStream(() -> os), materializer);
      final IOResult result = resultFuture.toCompletableFuture().get(3, TimeUnit.SECONDS);

      assertFalse(result.wasSuccessful());
      assertTrue(result.getError().getMessage().equals("Can't accept more data."));
    }

}
