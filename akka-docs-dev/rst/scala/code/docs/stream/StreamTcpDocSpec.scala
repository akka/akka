/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.stream.scaladsl.Concat
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.FlowGraphImplicits
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.UndefinedSink
import akka.stream.scaladsl.UndefinedSource
import akka.stream.testkit.AkkaSpec
import akka.util.ByteString
import cookbook.RecipeParseLines

class StreamTcpDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  //#setup
  import akka.stream.FlowMaterializer
  import akka.stream.scaladsl.StreamTcp
  import akka.stream.scaladsl.StreamTcp._

  implicit val sys = ActorSystem("stream-tcp-system")
  implicit val mat = FlowMaterializer()
  //#setup

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

  "simple repl client" ignore {
    val sys: ActorSystem = ???

    //#repl-client
    val connection: OutgoingConnection = StreamTcp().outgoingConnection(localhost)

    val repl = Flow[ByteString]
      .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
      .map(text => println("Server: " + text))
      .map(_ => readLine("> "))
      .map {
        case "q" =>
          sys.shutdown(); ByteString("BYE")
        case text => ByteString(s"$text")
      }

    connection.handleWith(repl)
    //#repl-client
  }

  "initial server banner echo server" ignore {
    val binding = StreamTcp().bind(localhost)

    //#welcome-banner-chat-server
    binding.connections runForeach { connection =>

      val serverLogic = Flow() { implicit b =>
        import FlowGraphImplicits._

        // to be filled in by StreamTCP
        val in = UndefinedSource[ByteString]
        val out = UndefinedSink[ByteString]

        val welcomeMsg =
          s"""|Welcome to: ${connection.localAddress}!
              |You are: ${connection.remoteAddress}!""".stripMargin

        val welcome = Source.single(ByteString(welcomeMsg))
        val echo = Flow[ByteString]
          .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
          .map(_ ++ "!!!")
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

  }
}
