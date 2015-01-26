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

import akka.stream.BindFailedException;
import akka.stream.StreamTcpException;
import akka.stream.StreamTest;
import akka.stream.javadsl.StreamTcp.IncomingConnection;
import akka.stream.javadsl.StreamTcp.ServerBinding;
import akka.stream.javadsl.japi.Function2;
import akka.stream.javadsl.japi.Procedure;
import akka.stream.testkit.AkkaSpec;
import akka.stream.testkit.TestUtils;
import akka.util.ByteString;

public class StreamTcpTest extends StreamTest {
  public StreamTcpTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("StreamTcpTest",
    AkkaSpec.testConf());
  
  final Sink<IncomingConnection> echoHandler =
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
    final ServerBinding binding = StreamTcp.get(system).bind(serverAddress);
    
    final MaterializedMap materializedServer = binding.connections().to(echoHandler).run(materializer);
    final Future<InetSocketAddress> serverFuture = binding.localAddress(materializedServer);
    final InetSocketAddress s = Await.result(serverFuture, FiniteDuration.create(5, TimeUnit.SECONDS));
    assertEquals(s.getPort(), serverAddress.getPort());
    
    final Source<ByteString> responseStream =
      Source.from(testInput).via(StreamTcp.get(system).outgoingConnection(serverAddress).flow());
    
    final Future<ByteString> resultFuture = responseStream.runFold(
        ByteString.empty(), new Function2<ByteString, ByteString, ByteString>() {
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
    final ServerBinding binding = StreamTcp.get(system).bind(serverAddress);
    
    final MaterializedMap materializedServer = binding.connections().to(echoHandler).run(materializer);
    final Future<InetSocketAddress> serverFuture = binding.localAddress(materializedServer);
    final InetSocketAddress s = Await.result(serverFuture, FiniteDuration.create(5, TimeUnit.SECONDS));
    assertEquals(s.getPort(), serverAddress.getPort());
    
    // bind again, to same port
    final MaterializedMap materializedServer2 = binding.connections().to(echoHandler).run(materializer);
    final Future<InetSocketAddress> serverFuture2 = binding.localAddress(materializedServer2);
    boolean bindFailed = false;
    try {
      Await.result(serverFuture2, FiniteDuration.create(5, TimeUnit.SECONDS));
    } catch (BindFailedException e) {
      // as expected
      bindFailed = true;
    }
    assertTrue("Expected BindFailedException, but nothing was reported", bindFailed);
  }
  
  @Test
  public void mustReportClientConnectFailure() throws Exception {
    
    final InetSocketAddress serverAddress = TestUtils.temporaryServerAddress("127.0.0.1", false);
    final Source<ByteString> responseStream =
      Source.from(testInput).via(StreamTcp.get(system).outgoingConnection(serverAddress).flow());
    final Future<ByteString> resultFuture = responseStream.runWith(Sink.<ByteString>head(), materializer);
    
    boolean streamTcpException = false;
    try {
      Await.result(resultFuture, FiniteDuration.create(5, TimeUnit.SECONDS));
    } catch (StreamTcpException e) { 
      // as expected
      streamTcpException = true;
    }
    assertTrue("Expected StreamTcpException, but nothing was reported", streamTcpException);
    
  }

}
