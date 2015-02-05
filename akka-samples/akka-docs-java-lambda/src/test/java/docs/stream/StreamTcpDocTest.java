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

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.FlexiMerge.ReadAllInputs;
import akka.stream.javadsl.*;
import akka.stream.javadsl.StreamTcp.IncomingConnection;
import akka.stream.javadsl.StreamTcp.OutgoingConnection;
import akka.stream.javadsl.StreamTcp.ServerBinding;
import akka.stream.stage.Context;
import akka.stream.stage.Directive;
import akka.stream.stage.PushStage;
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
  
  final InetSocketAddress localhost = new InetSocketAddress("127.0.0.1", 8889);
  
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
    //#echo-server-simple-bind
    final InetSocketAddress localhost = 
        new InetSocketAddress("127.0.0.1", 8889);
    final ServerBinding binding = StreamTcp.get(system).bind(localhost);
    //#echo-server-simple-bind

    //#echo-server-simple-handle
    final Source<IncomingConnection> connections = binding.connections();

    connections.runForeach(connection -> {
      System.out.println("New connection from: " + connection.remoteAddress());

      final Flow<ByteString, ByteString> echo = Flow.of(ByteString.class)
        .transform(() -> RecipeParseLines.parseLines("\n", 256))
        .map(s -> s + "!!!\n")
        .map(s -> ByteString.fromString(s));

      connection.handleWith(echo, mat);
    }, mat);
    //#echo-server-simple-handle
  }
  
  @Test
  public void actuallyWorkingClientServerApp() {
    final TestProbe serverProbe = new TestProbe(system);

    final ServerBinding binding = StreamTcp.get(system).bind(localhost);
    //#welcome-banner-chat-server
    binding.connections().runForeach(connection -> {
      // to be filled in by StreamTCP
      UndefinedSource<ByteString> in = UndefinedSource.create();
      UndefinedSink<ByteString> out = UndefinedSink.create();

      // server logic, parses incoming commands
      final PushStage<String, String> commandParser = new PushStage<String, String>() {
        @Override public Directive onPush(String elem, Context<String> ctx) {
          if (elem.equals("BYE")) 
            return ctx.finish();
           else
             return ctx.push(elem + "!");
          }
        };

      final String welcomeMsg = "Welcome to: " + connection.localAddress() + 
          " you are: " + connection.remoteAddress() + "!\n";

      final Source<ByteString> welcome = 
          Source.single(ByteString.fromString(welcomeMsg));
      final Flow<ByteString, ByteString> echo = Flow.of(ByteString.class)
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

      final Concat<ByteString> concat = Concat.create();
      
      final Flow<ByteString, ByteString> serverLogic = Flow.create(builder -> {
        builder
          // first we emit the welcome message,
          .addEdge(welcome, concat.first())
          // then we continue using the echo-logic Flow
          .addEdge(in, echo, concat.second())
          .addEdge(concat.out(), out);
        return new Pair<>(in, out);
      });

      connection.handleWith(serverLogic, mat);
    }, mat);

    //#welcome-banner-chat-server

    //#repl-client
    final OutgoingConnection connection = StreamTcp.get(system)
        .outgoingConnection(localhost);

    final PushStage<String, ByteString> replParser = new PushStage<String, ByteString>() {
      @Override public Directive onPush(String elem, Context<ByteString> ctx) {
        if (elem.equals("q"))
          return ctx.pushAndFinish(ByteString.fromString("BYE\n"));
        else
          return ctx.push(ByteString.fromString(elem + "\n"));
      }
    };

    final Flow<ByteString, ByteString> repl = Flow.of(ByteString.class)
      .transform(() -> RecipeParseLines.parseLines("\n", 256))
      .map(text -> {System.out.println("Server: " + text); return "next";})
      .map(elem -> readLine("> "))
      .transform(() -> replParser);

    connection.handleWith(repl, mat);
    //#repl-client

    serverProbe.expectMsg("Hello world");
    serverProbe.expectMsg("What a lovely day");
    serverProbe.expectMsg("BYE");
  }
  
}
