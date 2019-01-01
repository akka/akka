/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;

import akka.Done;
import akka.actor.ActorSystem;
import akka.japi.function.Function2;
import akka.japi.function.Procedure;
import akka.stream.BindFailedException;
import akka.stream.StreamTcpException;
import akka.stream.StreamTest;
import akka.stream.javadsl.Tcp.IncomingConnection;
import akka.stream.javadsl.Tcp.ServerBinding;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import akka.testkit.SocketUtil;
import akka.testkit.javadsl.EventFilter;
import akka.testkit.javadsl.TestKit;
import akka.util.ByteString;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// #setting-up-ssl-context
// imports
import akka.stream.TLSClientAuth;
import akka.stream.TLSProtocol;
import com.typesafe.sslconfig.akka.AkkaSSLConfig;
import java.security.KeyStore;
import javax.net.ssl.*;
import java.security.SecureRandom;
// #setting-up-ssl-context

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
    final InetSocketAddress serverAddress = SocketUtil.temporaryServerAddress(SocketUtil.RANDOM_LOOPBACK_ADDRESS(), false);
    final Source<IncomingConnection, CompletionStage<ServerBinding>> binding = Tcp.get(system)
        .bind(serverAddress.getHostString(), serverAddress.getPort());

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

    b.unbind();
  }

  @Test
  public void mustReportServerBindFailure() throws Exception {
    final InetSocketAddress serverAddress = SocketUtil.temporaryServerAddress(SocketUtil.RANDOM_LOOPBACK_ADDRESS(), false);
    final Source<IncomingConnection, CompletionStage<ServerBinding>> binding = Tcp.get(system)
        .bind(serverAddress.getHostString(), serverAddress.getPort());

    final CompletionStage<ServerBinding> future = binding.to(echoHandler).run(materializer);
    final ServerBinding b = future.toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(b.localAddress().getPort(), serverAddress.getPort());

    new TestKit(system) {{
      new EventFilter(BindException.class, system).occurrences(1).intercept(() -> {
        try {
          binding.to(echoHandler).run(materializer).toCompletableFuture().get(5, TimeUnit.SECONDS);
          assertTrue("Expected BindFailedException, but nothing was reported", false);
        } catch (ExecutionException e) {
          if (e.getCause() instanceof BindFailedException) {} // all good
          else throw new AssertionError("failed", e);
          // expected
          b.unbind();
        } catch (Exception e) {
          throw new AssertionError("failed", e);
        }
        return null;
      });
    }};
  }

  @Test
  public void mustReportClientConnectFailure() throws Throwable {
    final InetSocketAddress serverAddress = SocketUtil.notBoundServerAddress();
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

  // compile only sample
  public void constructSslContext() throws Exception {
    ActorSystem system = null;

    // #setting-up-ssl-context

    // -- setup logic ---

    AkkaSSLConfig sslConfig = AkkaSSLConfig.get(system);

    // Don't hardcode your password in actual code
    char[] password = "abcdef".toCharArray();

    // trust store and keys in one keystore
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(getClass().getResourceAsStream("/tcp-spec-keystore.p12"), password);

    TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
    tmf.init(keyStore);

    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
    keyManagerFactory.init(keyStore, password);

    // initial ssl context
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(keyManagerFactory.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

    // protocols
    SSLParameters defaultParams = sslContext.getDefaultSSLParameters();
    String[] defaultProtocols = defaultParams.getProtocols();
    String[] protocols = sslConfig.configureProtocols(defaultProtocols, sslConfig.config());
    defaultParams.setProtocols(protocols);

    // ciphers
    String[] defaultCiphers = defaultParams.getCipherSuites();
    String[] cipherSuites = sslConfig.configureCipherSuites(defaultCiphers, sslConfig.config());
    defaultParams.setCipherSuites(cipherSuites);

    TLSProtocol.NegotiateNewSession negotiateNewSession = TLSProtocol.negotiateNewSession()
        .withCipherSuites(cipherSuites)
        .withProtocols(protocols)
        .withParameters(defaultParams)
        .withClientAuth(TLSClientAuth.none());

    // #setting-up-ssl-context
  }

}
