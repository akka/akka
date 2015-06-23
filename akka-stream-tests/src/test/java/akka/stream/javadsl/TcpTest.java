/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.ClassRule;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import akka.stream.*;
import akka.stream.javadsl.Tcp.*;
import akka.japi.function.*;
import akka.stream.testkit.AkkaSpec;
import akka.stream.testkit.TestUtils;
import akka.util.ByteString;

public class TcpTest extends StreamTest {
  public TcpTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("TcpTest",
    AkkaSpec.testConf());

  final Sink<IncomingConnection, Future<BoxedUnit>> echoHandler =
      Sink.foreach(new Procedure<IncomingConnection>() {
        public void apply(IncomingConnection conn) {
          conn.handleWith(Flow.<ByteString>empty(), materializer);
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
    final Source<IncomingConnection, Future<ServerBinding>> binding = Tcp.get(system)
        .bind(serverAddress.getHostName(), serverAddress.getPort()); // TODO getHostString in Java7

    final Future<ServerBinding> future = binding.to(echoHandler).run(materializer);
    final ServerBinding b = Await.result(future, FiniteDuration.create(5, TimeUnit.SECONDS));
    assertEquals(b.localAddress().getPort(), serverAddress.getPort());

    final Future<ByteString> resultFuture = Source
        .from(testInput)
        // TODO getHostString in Java7
        .via(Tcp.get(system).outgoingConnection(serverAddress.getHostName(), serverAddress.getPort()))
        .runFold(ByteString.empty(),
            new Function2<ByteString, ByteString, ByteString>() {
              public ByteString apply(ByteString acc, ByteString elem) {
                return acc.concat(elem);
              }
            }, materializer);

    final byte[] result = Await.result(resultFuture, FiniteDuration.create(5, TimeUnit.SECONDS)).toArray();
    for (int i = 0; i < testInput.size(); i ++) {
      assertEquals(testInput.get(i).head(), result[i]);
    }
  }

  @Test
  public void mustReportServerBindFailure() throws Exception {
    final InetSocketAddress serverAddress = TestUtils.temporaryServerAddress("127.0.0.1", false);
    final Source<IncomingConnection, Future<ServerBinding>> binding = Tcp.get(system)
        .bind(serverAddress.getHostName(), serverAddress.getPort()); // TODO getHostString in Java7

    final Future<ServerBinding> future = binding.to(echoHandler).run(materializer);
    final ServerBinding b = Await.result(future, FiniteDuration.create(5, TimeUnit.SECONDS));
    assertEquals(b.localAddress().getPort(), serverAddress.getPort());

    try {
      Await.result(binding.to(echoHandler).run(materializer), FiniteDuration.create(5, TimeUnit.SECONDS));
      assertTrue("Expected BindFailedException, but nothing was reported", false);
    } catch (BindFailedException e) {
      // expected
    }
  }

  @Test
  public void mustReportClientConnectFailure() throws Exception {
    final InetSocketAddress serverAddress = TestUtils.temporaryServerAddress(
        "127.0.0.1", false);
    try {
      Await.result(
          Source.from(testInput)
              // TODO getHostString in Java7
              .viaMat(Tcp.get(system).outgoingConnection(serverAddress.getHostName(), serverAddress.getPort()),
                  Keep.<BoxedUnit, Future<OutgoingConnection>> right())
              .to(Sink.<ByteString> ignore())
              .run(materializer),
          FiniteDuration.create(5, TimeUnit.SECONDS));
      assertTrue("Expected StreamTcpException, but nothing was reported", false);
    } catch (StreamTcpException e) {
      // expected
    }
  }

}
