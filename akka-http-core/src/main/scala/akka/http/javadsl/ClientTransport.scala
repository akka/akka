/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import akka.stream.javadsl.Flow
import akka.util.ByteString
import akka.http.javadsl.settings.ClientConnectionSettings
import akka.http.impl.util.JavaMapping
import JavaMapping._
import JavaMapping.Implicits._
import akka.http.{ javadsl, scaladsl }

import scala.concurrent.Future

abstract class ClientTransport { outer ⇒
  def connectTo(host: String, port: Int, system: ActorSystem): Flow[ByteString, ByteString, CompletionStage[OutgoingConnection]]
}

object ClientTransport {
  def TCP(localAddress: Optional[InetSocketAddress], settings: ClientConnectionSettings): ClientTransport =
    scaladsl.ClientTransport.TCP(localAddress.asScala, settings.asScala).asJava

  def fromScala(scalaTransport: scaladsl.ClientTransport): ClientTransport =
    scalaTransport match {
      case j: JavaWrapper ⇒ j.delegate
      case x              ⇒ new ScalaWrapper(x)
    }
  def toScala(javaTransport: ClientTransport): scaladsl.ClientTransport =
    javaTransport match {
      case s: ScalaWrapper ⇒ s.delegate
      case x               ⇒ new JavaWrapper(x)
    }

  private class ScalaWrapper(val delegate: scaladsl.ClientTransport) extends ClientTransport {
    def connectTo(host: String, port: Int, system: ActorSystem): akka.stream.javadsl.Flow[ByteString, ByteString, CompletionStage[javadsl.OutgoingConnection]] = {
      import system.dispatcher
      JavaMapping.toJava(delegate.connectTo(host, port)(system))
    }
  }
  private class JavaWrapper(val delegate: ClientTransport) extends scaladsl.ClientTransport {
    def connectTo(host: String, port: Int)(implicit system: ActorSystem): akka.stream.scaladsl.Flow[ByteString, ByteString, Future[scaladsl.Http.OutgoingConnection]] = {
      import system.dispatcher
      JavaMapping.toScala(delegate.connectTo(host, port, system))
    }
  }
}