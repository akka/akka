/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.flow

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.util.ByteString

import scala.concurrent.duration._

object FromSinkAndSource {

  implicit val system: ActorSystem = ???

  def halfClosedTcpServer(): Unit = {
    // #halfClosedTcpServer
    // close in immediately
    val sink = Sink.cancelled[ByteString]
    // periodic tick out
    val source =
      Source.tick(1.second, 1.second, "tick").map(_ => ByteString(System.currentTimeMillis().toString + "\n"))

    val serverFlow = Flow.fromSinkAndSource(sink, source)

    Tcp().bind("127.0.0.1", 9999).runForeach { incomingConnection =>
      incomingConnection.handleWith(serverFlow)
    }
    // #halfClosedTcpServer
  }

}
