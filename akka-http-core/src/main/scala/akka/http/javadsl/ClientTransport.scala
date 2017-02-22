/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import akka.stream.javadsl.Flow
import akka.util.ByteString
import akka.http.javadsl.settings.ClientConnectionSettings
import akka.http.impl.util.JavaMapping
import JavaMapping._
import JavaMapping.Implicits._
import akka.annotation.ApiMayChange
import akka.http.{ javadsl, scaladsl }

import scala.concurrent.Future

/**
 * (Still unstable) SPI for implementors of custom client transports.
 */
@ApiMayChange
abstract class ClientTransport { outer ⇒
  def connectTo(host: String, port: Int, settings: ClientConnectionSettings, system: ActorSystem): Flow[ByteString, ByteString, CompletionStage[OutgoingConnection]]
}

/**
 * (Still unstable) entry point to create or access predefined client transports.
 */
@ApiMayChange
object ClientTransport {
  def TCP: ClientTransport = scaladsl.ClientTransport.TCP.asJava

  def proxy(proxyAddress: InetSocketAddress): ClientTransport =
    scaladsl.ClientTransport.proxy(proxyAddress).asJava

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
    def connectTo(host: String, port: Int, settings: ClientConnectionSettings, system: ActorSystem): akka.stream.javadsl.Flow[ByteString, ByteString, CompletionStage[javadsl.OutgoingConnection]] = {
      import system.dispatcher
      JavaMapping.toJava(delegate.connectTo(host, port, settings.asScala)(system))
    }
  }
  private class JavaWrapper(val delegate: ClientTransport) extends scaladsl.ClientTransport {
    def connectTo(host: String, port: Int, settings: scaladsl.settings.ClientConnectionSettings)(implicit system: ActorSystem): akka.stream.scaladsl.Flow[ByteString, ByteString, Future[scaladsl.Http.OutgoingConnection]] = {
      import system.dispatcher
      JavaMapping.toScala(delegate.connectTo(host, port, settings.asJava, system))
    }
  }
}