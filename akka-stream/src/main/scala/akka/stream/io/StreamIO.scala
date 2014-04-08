/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.util.ByteString
import org.reactivestreams.api.{ Processor, Producer, Consumer }
import java.net.InetSocketAddress
import akka.actor._
import scala.collection._
import scala.concurrent.duration.FiniteDuration
import akka.io.Inet.SocketOption
import akka.io.{ IO, Tcp }
import akka.stream.impl.{ ActorPublisher, ExposedPublisher, ActorProcessor }
import akka.stream.MaterializerSettings
import akka.io.Tcp.CommandFailed
import akka.stream.io.StreamTcp.OutgoingTcpConnection

object StreamIO {
  trait Extension extends akka.actor.Extension {
    def manager: ActorRef
  }

  def apply[T <: Extension](key: ExtensionId[T])(implicit system: ActorSystem): ActorRef = key(system).manager

}

object StreamTcp extends ExtensionId[StreamTcpExt] with ExtensionIdProvider {

  override def lookup = StreamTcp
  override def createExtension(system: ExtendedActorSystem): StreamTcpExt = new StreamTcpExt(system)
  override def get(system: ActorSystem): StreamTcpExt = super.get(system)

  case class OutgoingTcpConnection(remoteAddress: InetSocketAddress,
                                   localAddress: InetSocketAddress,
                                   processor: Processor[ByteString, ByteString]) {
    def outputStream: Consumer[ByteString] = processor
    def inputStream: Producer[ByteString] = processor
  }

  case class TcpServerBinding(localAddress: InetSocketAddress,
                              connectionStream: Producer[IncomingTcpConnection])

  case class IncomingTcpConnection(remoteAddress: InetSocketAddress,
                                   inputStream: Producer[ByteString],
                                   outputStream: Consumer[ByteString]) {
    def handleWith(processor: Processor[ByteString, ByteString]): Unit = {
      processor.produceTo(outputStream)
      inputStream.produceTo(processor)
    }
  }

  case class Connect(remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil,
                     timeout: Option[FiniteDuration] = None,
                     settings: MaterializerSettings)

  case class Bind(localAddress: InetSocketAddress,
                  backlog: Int = 100,
                  options: immutable.Traversable[SocketOption] = Nil,
                  settings: MaterializerSettings)

}

/**
 * INTERNAL API
 */
private[akka] class StreamTcpExt(system: ExtendedActorSystem) extends StreamIO.Extension {
  val manager: ActorRef = system.systemActorOf(Props[StreamTcpManager], name = "IO-TCP-STREAM")
}

/**
 * INTERNAL API
 */
private[akka] object StreamTcpManager {
  private[akka] case class ExposedProcessor(processor: Processor[ByteString, ByteString])
}

/**
 * INTERNAL API
 */
private[akka] class StreamTcpManager extends Actor {
  import StreamTcpManager._

  def receive: Receive = {
    case StreamTcp.Connect(remoteAddress, localAddress, options, timeout, settings) ⇒
      val processorActor = context.actorOf(TcpStreamActor.outboundProps(
        Tcp.Connect(remoteAddress, localAddress, options, timeout, pullMode = true),
        requester = sender(),
        settings))
      processorActor ! ExposedProcessor(new ActorProcessor[ByteString, ByteString](processorActor))

    case StreamTcp.Bind(localAddress, backlog, options, settings) ⇒
      val publisherActor = context.actorOf(TcpListenStreamActor.props(
        Tcp.Bind(context.system.deadLetters, localAddress, backlog, options, pullMode = true),
        requester = sender(),
        settings))
      publisherActor ! ExposedPublisher(new ActorPublisher(publisherActor))
  }

}

