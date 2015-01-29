/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.stream.scaladsl.Concat
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.FlowGraphImplicits
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.UndefinedSink
import akka.stream.scaladsl.UndefinedSource
import akka.stream.stage.{ PushStage, Directive, Context, PushPullStage }
import akka.stream.testkit.AkkaSpec
import akka.testkit.TestProbe
import akka.util.ByteString
import cookbook.RecipeParseLines

class StreamTcpDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  //#setup
  import akka.stream.ActorFlowMaterializer
  import akka.stream.scaladsl.StreamTcp
  import akka.stream.scaladsl.StreamTcp._

  implicit val sys = ActorSystem("stream-tcp-system")
  implicit val mat = ActorFlowMaterializer()
  //#setup

  // silence sysout
  def println(s: String) = ()

  val localhost = new InetSocketAddress("127.0.0.1", 8888)

  "simple server connection" ignore {
    //#echo-server-simple-bind
    val localhost = new InetSocketAddress("127.0.0.1", 8888)
    val binding = StreamTcp().bind(localhost)
    //#echo-server-simple-bind

    //#echo-server-simple-handle
    val connections: Source[IncomingConnection] = binding.connections

    connections runForeach { connection =>
      println(s"New connection from: ${connection.remoteAddress}")

      val echo = Flow[ByteString]
        .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
        .map(_ ++ "!!!\n")
        .map(ByteString(_))

      connection.handleWith(echo)
    }
    //#echo-server-simple-handle
  }

  "actually working client-server CLI app" in {
    val serverProbe = TestProbe()

    val binding = StreamTcp().bind(localhost)
    //#welcome-banner-chat-server
    binding.connections runForeach { connection =>

      val serverLogic = Flow() { implicit b =>
        import FlowGraphImplicits._

        // to be filled in by StreamTCP
        val in = UndefinedSource[ByteString]
        val out = UndefinedSink[ByteString]

        // server logic, parses incoming commands
        val commandParser = new PushStage[String, String] {
          override def onPush(elem: String, ctx: Context[String]): Directive = {
            elem match {
              case "BYE" ⇒ ctx.finish()
              case _     ⇒ ctx.push(elem + "!")
            }
          }
        }

        import connection._
        val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!\n"

        val welcome = Source.single(ByteString(welcomeMsg))
        val echo = Flow[ByteString]
          .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
          //#welcome-banner-chat-server
          .map { command ⇒ serverProbe.ref ! command; command }
          //#welcome-banner-chat-server
          .transform(() ⇒ commandParser)
          .map(_ ++ "\n")
          .map(ByteString(_))

        val concat = Concat[ByteString]
        // first we emit the welcome message,
        welcome ~> concat.first
        // then we continue using the echo-logic Flow
        in ~> echo ~> concat.second

        concat.out ~> out
        (in, out)
      }

      connection.handleWith(serverLogic)
    }

    //#welcome-banner-chat-server

    val input = new AtomicReference("Hello world" :: "What a lovely day" :: Nil)
    def readLine(prompt: String): String = {
      input.get() match {
        case all @ cmd :: tail if input.compareAndSet(all, tail) ⇒ cmd
        case _ ⇒ "q"
      }
    }

    //#repl-client
    val connection: OutgoingConnection = StreamTcp().outgoingConnection(localhost)

    val replParser = new PushStage[String, ByteString] {
      override def onPush(elem: String, ctx: Context[ByteString]): Directive = {
        elem match {
          case "q" ⇒ ctx.pushAndFinish(ByteString("BYE\n"))
          case _   ⇒ ctx.push(ByteString(s"$elem\n"))
        }
      }
    }

    val repl = Flow[ByteString]
      .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
      .map(text => println("Server: " + text))
      .map(_ => readLine("> "))
      //#repl-client
      .transform(() ⇒ replParser)

    connection.handleWith(repl)
    //#repl-client

    serverProbe.expectMsg("Hello world")
    serverProbe.expectMsg("What a lovely day")
    serverProbe.expectMsg("BYE")
  }
}
