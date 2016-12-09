/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream.io;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

import akka.NotUsed;
import akka.stream.javadsl.Framing;
import docs.AbstractJavaTest;
import docs.stream.SilenceSystemOut;
import java.net.InetSocketAddress;

import docs.util.SocketUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Future;

import akka.actor.ActorSystem;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.javadsl.Tcp.*;
import akka.stream.stage.*;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import akka.util.ByteString;

public class StreamTcpDocTest extends AbstractJavaTest {

  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamTcpDocTest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }


  final SilenceSystemOut.System System = SilenceSystemOut.get();

  private final ConcurrentLinkedQueue<String> input = new ConcurrentLinkedQueue<String>();
  {
    input.add("Hello world");
    input.add("What a lovely day");
  }

  private String readLine(String prompt) {
    String s = input.poll();
    return (s == null ? "q": s);
  }

  @Test
  public void demonstrateSimpleServerConnection() {
    {
      //#echo-server-simple-bind
      // IncomingConnection and ServerBinding imported from Tcp
      final Source<IncomingConnection, CompletionStage<ServerBinding>> connections =
          Tcp.get(system).bind("127.0.0.1", 8889);
      //#echo-server-simple-bind
    }
    {

      final InetSocketAddress localhost = SocketUtils.temporaryServerAddress();
      final Source<IncomingConnection, CompletionStage<ServerBinding>> connections =
        Tcp.get(system).bind(localhost.getHostName(), localhost.getPort()); // TODO getHostString in Java7

      //#echo-server-simple-handle
      connections.runForeach(connection -> {
        System.out.println("New connection from: " + connection.remoteAddress());

        final Flow<ByteString, ByteString, NotUsed> echo = Flow.of(ByteString.class)
          .via(Framing.delimiter(ByteString.fromString("\n"), 256, FramingTruncation.DISALLOW))
          .map(ByteString::utf8String)
          .map(s -> s + "!!!\n")
          .map(ByteString::fromString);

        connection.handleWith(echo, mat);
      }, mat);
      //#echo-server-simple-handle
    }
  }

  @Test
  public void actuallyWorkingClientServerApp() {

    final InetSocketAddress localhost = SocketUtils.temporaryServerAddress();

    final TestProbe serverProbe = new TestProbe(system);

    final Source<IncomingConnection,CompletionStage<ServerBinding>> connections =
        Tcp.get(system).bind(localhost.getHostString(), localhost.getPort());
    //#welcome-banner-chat-server
    connections.runForeach(connection -> {
      // server logic, parses incoming commands
      final Flow<String, String, NotUsed> commandParser =
          Flow.<String>create()
            .takeWhile(elem -> !elem.equals("BYE"))
            .map(elem -> elem + "!");

      final String welcomeMsg = "Welcome to: " + connection.localAddress() +
          " you are: " + connection.remoteAddress() + "!";

      final Source<String, NotUsed> welcome = Source.single(welcomeMsg);
      final Flow<ByteString, ByteString, NotUsed> serverLogic =
          Flow.of(ByteString.class)
            .via(Framing.delimiter(ByteString.fromString("\n"), 256, FramingTruncation.DISALLOW))
            .map(ByteString::utf8String)
            //#welcome-banner-chat-server
            .map(command -> {
              serverProbe.ref().tell(command, null);
              return command;
            })
            //#welcome-banner-chat-server
            .via(commandParser)
            .merge(welcome)
            .map(s ->  s + "\n")
            .map(ByteString::fromString);

      connection.handleWith(serverLogic, mat);
    }, mat);

    //#welcome-banner-chat-server

    {
    //#repl-client
      final Flow<ByteString, ByteString, CompletionStage<OutgoingConnection>> connection =
          Tcp.get(system).outgoingConnection("127.0.0.1", 8889);
      //#repl-client
    }

    {
      final Flow<ByteString, ByteString, CompletionStage<OutgoingConnection>> connection =
          Tcp.get(system).outgoingConnection(localhost.getHostString(), localhost.getPort());
      //#repl-client
      final Flow<String, ByteString, NotUsed> replParser =
          Flow.<String>create()
            .takeWhile(elem -> !elem.equals("q"))
            .concat(Source.single("BYE")) // will run after the original flow completes
            .map(elem -> ByteString.fromString(elem + "\n"));

      final Flow<ByteString, ByteString, NotUsed> repl = Flow.of(ByteString.class)
        .via(Framing.delimiter(ByteString.fromString("\n"), 256, FramingTruncation.DISALLOW))
        .map(ByteString::utf8String)
        .map(text -> {System.out.println("Server: " + text); return "next";})
        .map(elem -> readLine("> "))
        .via(replParser);

      connection.join(repl).run(mat);
      //#repl-client
    }



    serverProbe.expectMsg("Hello world");
    serverProbe.expectMsg("What a lovely day");
    serverProbe.expectMsg("BYE");
  }

}