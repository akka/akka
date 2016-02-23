/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.net.InetSocketAddress

import akka.actor.{ ActorSystem, ActorSystemImpl }
import akka.event.Logging
import akka.stream.scaladsl._
import akka.stream.{ ActorAttributes, ActorMaterializer }
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }

object TcpLeakApp extends App {
  val testConf: Config = ConfigFactory.parseString(
    """
    akka.loglevel = DEBUG
    akka.log-dead-letters = on
    akka.io.tcp.trace-logging = on""")
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val fm = ActorMaterializer()

  import system.dispatcher

  val tcpFlow = Tcp().outgoingConnection(new InetSocketAddress("127.0.0.1", 1234)).named("TCP-outgoingConnection")
  List
    .fill(100)(
      Source
        .single(ByteString("FOO"))
        .log("outerFlow-beforeTcpFlow").withAttributes(ActorAttributes.logLevels(Logging.DebugLevel, Logging.ErrorLevel, Logging.ErrorLevel))
        .via(tcpFlow)
        .log("outerFlow-afterTcpFlow").withAttributes(ActorAttributes.logLevels(Logging.DebugLevel, Logging.ErrorLevel, Logging.ErrorLevel))
        .toMat(Sink.head)(Keep.right).run())
    .last
    .onComplete {
      case error ⇒
        println(s"Error: $error")
        Thread.sleep(10000)
        println("===================== \n\n" + system.asInstanceOf[ActorSystemImpl].printTree + "\n\n========================")
    }

  readLine()
  system.terminate()
}
