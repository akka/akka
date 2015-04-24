/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import docs.stream.cookbook.RecipeParseLines;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import util.SocketUtils;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.*;
import akka.stream.javadsl.FlexiMerge.ReadAllInputs;
import akka.stream.javadsl.*;
import akka.stream.javadsl.StreamTcp.*;
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

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

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
      // IncomingConnection and ServerBinding imported from StreamTcp
      final Source<IncomingConnection, Future<ServerBinding>> connections =
          StreamTcp.get(system).bind("127.0.0.1", 8889);
      //#echo-server-simple-bind
    }
    {
      
      final InetSocketAddress localhost = SocketUtils.temporaryServerAddress();
      final Source<IncomingConnection, Future<ServerBinding>> connections =
        StreamTcp.get(system).bind(localhost.getHostName(), localhost.getPort()); // TODO getHostString in Java7

      //#echo-server-simple-handle
      connections.runForeach(connection -> {
        System.out.println("New connection from: " + connection.remoteAddress());

        final Flow<ByteString, ByteString, BoxedUnit> echo = Flow.of(ByteString.class)
          .transform(() -> RecipeParseLines.parseLines("\n", 256))
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

    final Source<IncomingConnection,Future<ServerBinding>> connections =
        StreamTcp.get(system).bind(localhost.getHostName(), localhost.getPort()); // TODO getHostString in Java7
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

      final Source<ByteString, BoxedUnit> welcome =
          Source.single(ByteString.fromString(welcomeMsg));
      final Flow<ByteString, ByteString, BoxedUnit> echoFlow =
          Flow.of(ByteString.class)
            .transform(() -> RecipeParseLines.parseLines("\n", 256))
            //#welcome-banner-chat-server
            .map(command -> {
              serverProbe.ref().tell(command, null);
              return command;
            })
            //#welcome-banner-chat-server
            .transform(() -> commandParser)
            .map(s ->  s + "\n")
            .map(s -> ByteString.fromString(s));

      final Flow<ByteString, ByteString, BoxedUnit> serverLogic =
          Flow.factory().create(builder -> {
            final UniformFanInShape<ByteString, ByteString> concat =
                builder.graph(Concat.create());
            final FlowShape<ByteString, ByteString> echo = builder.graph(echoFlow);

            builder
              .from(welcome).to(concat)
              .from(echo).to(concat);

            return new Pair<>(echo.inlet(), concat.out());
      });

      connection.handleWith(serverLogic, mat);
    }, mat);

    //#welcome-banner-chat-server

    {
    //#repl-client
      final Flow<ByteString, ByteString, Future<OutgoingConnection>> connection =
          StreamTcp.get(system).outgoingConnection("127.0.0.1", 8889);
      //#repl-client
    }
    
    {
      final Flow<ByteString, ByteString, Future<OutgoingConnection>> connection =
          StreamTcp.get(system).outgoingConnection(localhost.getHostName(), localhost.getPort()); // TODO getHostString in Java7
      //#repl-client
  
      final PushStage<String, ByteString> replParser = new PushStage<String, ByteString>() {
        @Override public SyncDirective onPush(String elem, Context<ByteString> ctx) {
          if (elem.equals("q"))
            return ctx.pushAndFinish(ByteString.fromString("BYE\n"));
          else
            return ctx.push(ByteString.fromString(elem + "\n"));
        }
      };
  
      final Flow<ByteString, ByteString, BoxedUnit> repl = Flow.of(ByteString.class)
        .transform(() -> RecipeParseLines.parseLines("\n", 256))
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