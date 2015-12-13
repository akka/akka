/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream.io

import java.util.concurrent.atomic.AtomicReference

import akka.stream._
import akka.stream.scaladsl.Tcp._
import akka.stream.scaladsl._
import akka.stream.stage.{ Context, PushStage, SyncDirective }
import akka.stream.testkit.AkkaSpec
import akka.testkit.TestProbe
import akka.util.ByteString
import docs.utils.TestUtils

import scala.concurrent.Future

class StreamTcpDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  // silence sysout
  def println(s: String) = ()

  "simple server connection" in {
    {
      //#echo-server-simple-bind
      val binding: Future[ServerBinding] =
        Tcp().bind("127.0.0.1", 8888).to(Sink.ignore).run()

      binding.map { b =>
        b.unbind() onComplete {
          case _ => // ...
        }
      }
      //#echo-server-simple-bind
    }
    {
      val (host, port) = TestUtils.temporaryServerHostnameAndPort()
      //#echo-server-simple-handle
      import akka.stream.io.Framing

      val connections: Source[IncomingConnection, Future[ServerBinding]] =
        Tcp().bind(host, port)
      connections runForeach { connection =>
        println(s"New connection from: ${connection.remoteAddress}")

        val echo = Flow[ByteString]
          .via(Framing.delimiter(
            ByteString("\n"),
            maximumFrameLength = 256,
            allowTruncation = true))
          .map(_.utf8String)
          .map(_ + "!!!\n")
          .map(ByteString(_))

        connection.handleWith(echo)
      }
      //#echo-server-simple-handle
    }
  }

  "initial server banner echo server" in {
    val localhost = TestUtils.temporaryServerAddress()
    val connections = Tcp().bind(localhost.getHostName, localhost.getPort) // TODO getHostString in Java7
    val serverProbe = TestProbe()

    import akka.stream.io.Framing
    //#welcome-banner-chat-server

    connections runForeach { connection =>

      val serverLogic = Flow.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        // server logic, parses incoming commands
        val commandParser = new PushStage[String, String] {
          override def onPush(elem: String, ctx: Context[String]): SyncDirective = {
            elem match {
              case "BYE" ⇒ ctx.finish()
              case _     ⇒ ctx.push(elem + "!")
            }
          }
        }

        import connection._
        val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!\n"

        val welcome = Source.single(ByteString(welcomeMsg))
        val echo = b.add(Flow[ByteString]
          .via(Framing.delimiter(
            ByteString("\n"),
            maximumFrameLength = 256,
            allowTruncation = true))
          .map(_.utf8String)
          //#welcome-banner-chat-server
          .map { command ⇒ serverProbe.ref ! command; command }
          //#welcome-banner-chat-server
          .transform(() ⇒ commandParser)
          .map(_ + "\n")
          .map(ByteString(_)))

        val concat = b.add(Concat[ByteString]())
        // first we emit the welcome message,
        welcome ~> concat.in(0)
        // then we continue using the echo-logic Flow
        echo.outlet ~> concat.in(1)

        FlowShape(echo.inlet, concat.out)
      })

      connection.handleWith(serverLogic)
    }
    //#welcome-banner-chat-server

    import akka.stream.io.Framing

    val input = new AtomicReference("Hello world" :: "What a lovely day" :: Nil)
    def readLine(prompt: String): String = {
      input.get() match {
        case all @ cmd :: tail if input.compareAndSet(all, tail) ⇒ cmd
        case _ ⇒ "q"
      }
    }

    {
      //#repl-client
      val connection = Tcp().outgoingConnection("127.0.0.1", 8888)
      //#repl-client
    }

    {
      val connection = Tcp().outgoingConnection(localhost)
      //#repl-client

      val replParser = new PushStage[String, ByteString] {
        override def onPush(elem: String, ctx: Context[ByteString]): SyncDirective = {
          elem match {
            case "q" ⇒ ctx.pushAndFinish(ByteString("BYE\n"))
            case _   ⇒ ctx.push(ByteString(s"$elem\n"))
          }
        }
      }

      val repl = Flow[ByteString]
        .via(Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = 256,
          allowTruncation = true))
        .map(_.utf8String)
        .map(text => println("Server: " + text))
        .map(_ => readLine("> "))
        .transform(() ⇒ replParser)

      connection.join(repl).run()
    }
    //#repl-client

    serverProbe.expectMsg("Hello world")
    serverProbe.expectMsg("What a lovely day")
    serverProbe.expectMsg("BYE")
  }
}
