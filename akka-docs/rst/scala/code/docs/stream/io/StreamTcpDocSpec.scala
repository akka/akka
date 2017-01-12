/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream.io

import java.util.concurrent.atomic.AtomicReference

import akka.stream._
import akka.stream.scaladsl.Tcp._
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import akka.testkit.TestProbe
import akka.util.ByteString

import scala.concurrent.Future
import akka.testkit.SocketUtil

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
      val (host, port) = SocketUtil.temporaryServerHostnameAndPort()
      //#echo-server-simple-handle
      import akka.stream.scaladsl.Framing

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
    val localhost = SocketUtil.temporaryServerAddress()
    val connections = Tcp().bind(localhost.getHostName, localhost.getPort) // TODO getHostString in Java7
    val serverProbe = TestProbe()

    import akka.stream.scaladsl.Framing
    //#welcome-banner-chat-server

    connections.runForeach { connection =>

      // server logic, parses incoming commands
      val commandParser = Flow[String].takeWhile(_ != "BYE").map(_ + "!")

      import connection._
      val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!"
      val welcome = Source.single(welcomeMsg)

      val serverLogic = Flow[ByteString]
        .via(Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = 256,
          allowTruncation = true))
        .map(_.utf8String)
        //#welcome-banner-chat-server
        .map { command => serverProbe.ref ! command; command }
        //#welcome-banner-chat-server
        .via(commandParser)
        // merge in the initial banner after parser
        .merge(welcome)
        .map(_ + "\n")
        .map(ByteString(_))

      connection.handleWith(serverLogic)
    }
    //#welcome-banner-chat-server

    import akka.stream.scaladsl.Framing

    val input = new AtomicReference("Hello world" :: "What a lovely day" :: Nil)
    def readLine(prompt: String): String = {
      input.get() match {
        case all @ cmd :: tail if input.compareAndSet(all, tail) => cmd
        case _ => "q"
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

      val replParser =
        Flow[String].takeWhile(_ != "q")
          .concat(Source.single("BYE"))
          .map(elem => ByteString(s"$elem\n"))

      val repl = Flow[ByteString]
        .via(Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = 256,
          allowTruncation = true))
        .map(_.utf8String)
        .map(text => println("Server: " + text))
        .map(_ => readLine("> "))
        .via(replParser)

      connection.join(repl).run()
      //#repl-client
    }

    serverProbe.expectMsg("Hello world")
    serverProbe.expectMsg("What a lovely day")
    serverProbe.expectMsg("BYE")
  }
}
