/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.net.BindException;

import akka.Done;
import akka.NotUsed;
import org.junit.ClassRule;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import akka.stream.*;
import akka.stream.javadsl.Tcp.*;
import akka.japi.function.*;
import akka.testkit.AkkaSpec;
import akka.stream.testkit.TestUtils;
import akka.util.ByteString;
import akka.testkit.JavaTestKit;
import akka.testkit.AkkaJUnitActorSystemResource;

public class TcpTest extends StreamTest {
  public TcpTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("TcpTest",
    AkkaSpec.testConf());

  final Sink<IncomingConnection, CompletionStage<Done>> echoHandler =
      Sink.foreach(new Procedure<IncomingConnection>() {
        public void apply(IncomingConnection conn) {
          conn.handleWith(Flow.of(ByteString.class), materializer);
        }
      });

  final List<ByteString> testInput = new ArrayList<ByteString>();
  {
    for (char c = 'a'; c <= 'z'; c++) {
      testInput.add(ByteString.fromString(String.valueOf(c)));
    }
  }

  @Test
  public void mustWorkInHappyCase() throws Exception {
    final InetSocketAddress serverAddress = TestUtils.temporaryServerAddress("127.0.0.1", false);
    final Source<IncomingConnection, CompletionStage<ServerBinding>> binding = Tcp.get(system)
        .bind(serverAddress.getHostName(), serverAddress.getPort()); // TODO getHostString in Java7

    final CompletionStage<ServerBinding> future = binding.to(echoHandler).run(materializer);
    final ServerBinding b = future.toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(b.localAddress().getPort(), serverAddress.getPort());

    final CompletionStage<ByteString> resultFuture = Source
        .from(testInput)
        .via(Tcp.get(system).outgoingConnection(serverAddress.getHostString(), serverAddress.getPort()))
        .runFold(ByteString.empty(),
            new Function2<ByteString, ByteString, ByteString>() {
              public ByteString apply(ByteString acc, ByteString elem) {
                return acc.concat(elem);
              }
            }, materializer);

    final byte[] result = resultFuture.toCompletableFuture().get(5, TimeUnit.SECONDS).toArray();
    for (int i = 0; i < testInput.size(); i ++) {
      assertEquals(testInput.get(i).head(), result[i]);
    }
  }

  @Test
  public void mustReportServerBindFailure() throws Exception {
    final InetSocketAddress serverAddress = TestUtils.temporaryServerAddress("127.0.0.1", false);
    final Source<IncomingConnection, CompletionStage<ServerBinding>> binding = Tcp.get(system)
        .bind(serverAddress.getHostName(), serverAddress.getPort()); // TODO getHostString in Java7

    final CompletionStage<ServerBinding> future = binding.to(echoHandler).run(materializer);
    final ServerBinding b = future.toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(b.localAddress().getPort(), serverAddress.getPort());

    new JavaTestKit(system) {{
      new EventFilter<Void>(BindException.class) {
        @Override
        protected Void run() {
          try {
            binding.to(echoHandler).run(materializer).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue("Expected BindFailedException, but nothing was reported", false);
          } catch (ExecutionException e) {
            if (e.getCause() instanceof BindFailedException) {} // all good
            else throw new AssertionError("failed", e);
            // expected
          } catch (Exception e) {
            throw new AssertionError("failed", e);
          }
          return null;
        }
      }.occurrences(1).exec();
    }};
  }

  @Test
  public void mustReportClientConnectFailure() throws Throwable {
    final InetSocketAddress serverAddress = TestUtils.temporaryServerAddress(
        "127.0.0.1", false);
    try {
      try {
        Source.from(testInput)
            .viaMat(Tcp.get(system).outgoingConnection(serverAddress.getHostString(), serverAddress.getPort()),
                Keep.right())
            .to(Sink.<ByteString> ignore()).run(materializer).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue("Expected StreamTcpException, but nothing was reported", false);
      } catch (ExecutionException e) {
        throw e.getCause();
      }
    } catch (StreamTcpException e) {
      // expected
    }
  }

}
