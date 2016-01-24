/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream.io;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

import akka.NotUsed;
import akka.stream.io.Framing;
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

public class StreamTcpDocTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamTcpDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

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
          .via(Framing.delimiter(ByteString.fromString("\n"), 256, false))
          .map(bytes -> bytes.utf8String())
          .map(s -> s + "!!!\n")
          .map(s -> ByteString.fromString(s));

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
        Tcp.get(system).bind(localhost.getHostName(), localhost.getPort()); // TODO getHostString in Java7
    //#welcome-banner-chat-server
    connections.runForeach(connection -> {
      // server logic, parses incoming commands
      final PushStage<String, String> commandParser = new PushStage<String, String>() {
        @Override public SyncDirective onPush(String elem, Context<String> ctx) {
          if (elem.equals("BYE"))
            return ctx.finish();
          else
            return ctx.push(elem + "!");
        }
      };

      final String welcomeMsg = "Welcome to: " + connection.localAddress() +
          " you are: " + connection.remoteAddress() + "!\n";

      final Source<ByteString, NotUsed> welcome =
          Source.single(ByteString.fromString(welcomeMsg));
      final Flow<ByteString, ByteString, NotUsed> echoFlow =
          Flow.of(ByteString.class)
            .via(Framing.delimiter(ByteString.fromString("\n"), 256, false))
            .map(bytes -> bytes.utf8String())
            //#welcome-banner-chat-server
            .map(command -> {
              serverProbe.ref().tell(command, null);
              return command;
            })
            //#welcome-banner-chat-server
            .transform(() -> commandParser)
            .map(s ->  s + "\n")
            .map(s -> ByteString.fromString(s));

      final Flow<ByteString, ByteString, NotUsed> serverLogic =
          Flow.fromGraph(GraphDSL.create(builder -> {
            final UniformFanInShape<ByteString, ByteString> concat =
                builder.add(Concat.create());
            final FlowShape<ByteString, ByteString> echo = builder.add(echoFlow);

            builder
              .from(builder.add(welcome)).toFanIn(concat)
              .from(echo).toFanIn(concat);

            return FlowShape.of(echo.in(), concat.out());
      }));

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

      final PushStage<String, ByteString> replParser = new PushStage<String, ByteString>() {
        @Override public SyncDirective onPush(String elem, Context<ByteString> ctx) {
          if (elem.equals("q"))
            return ctx.pushAndFinish(ByteString.fromString("BYE\n"));
          else
            return ctx.push(ByteString.fromString(elem + "\n"));
        }
      };

      final Flow<ByteString, ByteString, NotUsed> repl = Flow.of(ByteString.class)
        .via(Framing.delimiter(ByteString.fromString("\n"), 256, false))
        .map(bytes -> bytes.utf8String())
        .map(text -> {System.out.println("Server: " + text); return "next";})
        .map(elem -> readLine("> "))
        .transform(() -> replParser);

      connection.join(repl).run(mat);
    //#repl-client
    }

    serverProbe.expectMsg("Hello world");
    serverProbe.expectMsg("What a lovely day");
    serverProbe.expectMsg("BYE");
  }

}