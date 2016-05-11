/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.Future
import scala.concurrent.Promise
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.RootActorPath
import akka.dispatch.sysmsg.SystemMessage
import akka.remote.EndpointManager.Send
import akka.remote.RemoteActorRef
import akka.remote.UniqueAddress
import akka.remote.artery.InboundReplyJunction.ReplySubject
import akka.stream.Materializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.remote.artery.OutboundReplyJunction.OutboundReplyIngress
import akka.stream.scaladsl.Keep

/**
 * INTERNAL API
 *
 * Thread-safe, mutable holder for association state. Main entry point for remote destined message to a specific
 * remote address.
 */
private[akka] class Association(
  val transport: ArteryTransport,
  val materializer: Materializer,
  override val remoteAddress: Address,
  override val replySubject: ReplySubject) extends OutboundContext {

  @volatile private[this] var queue: SourceQueueWithComplete[Send] = _
  @volatile private[this] var systemMessageQueue: SourceQueueWithComplete[Send] = _
  @volatile private[this] var _outboundReplyIngress: OutboundReplyIngress = _

  def outboundReplyIngress: OutboundReplyIngress = {
    if (_outboundReplyIngress eq null)
      throw new IllegalStateException("outboundReplyIngress not initialized yet")
    _outboundReplyIngress
  }

  override def localAddress: UniqueAddress = transport.localAddress

  // FIXME we also need to be able to switch to new uid
  private val _uniqueRemoteAddress = Promise[UniqueAddress]()
  override def uniqueRemoteAddress: Future[UniqueAddress] = _uniqueRemoteAddress.future
  override def completeRemoteAddress(a: UniqueAddress): Unit = {
    require(a.address == remoteAddress, s"Wrong UniqueAddress got [$a.address], expected [$remoteAddress]")
    _uniqueRemoteAddress.trySuccess(a)
  }

  def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    // TODO: lookup subchannel
    // FIXME: Use a different envelope than the old Send, but make sure the new is handled by deadLetters properly
    message match {
      case _: SystemMessage ⇒
        implicit val ec = materializer.executionContext
        systemMessageQueue.offer(Send(message, senderOption, recipient, None)).onFailure {
          case e ⇒
            // FIXME proper error handling, and quarantining
            println(s"# System message dropped, due to $e") // FIXME
        }
      case _ ⇒
        queue.offer(Send(message, senderOption, recipient, None))
    }
  }

  // FIXME we should be able to Send without a recipient ActorRef
  override val dummyRecipient: RemoteActorRef =
    transport.provider.resolveActorRef(RootActorPath(remoteAddress) / "system" / "dummy").asInstanceOf[RemoteActorRef]

  def quarantine(uid: Option[Int]): Unit = ()

  // Idempotent
  def associate(): Unit = {
    // FIXME detect and handle stream failure, e.g. handshake timeout
    if (queue eq null)
      queue = Source.queue(256, OverflowStrategy.dropBuffer)
        .to(transport.outbound(this)).run()(materializer)
    if (systemMessageQueue eq null) {
      val (q, control) = Source.queue(256, OverflowStrategy.dropBuffer)
        .toMat(transport.outboundSystemMessage(this))(Keep.both)
        .run()(materializer)
      systemMessageQueue = q
      _outboundReplyIngress = control
    }
  }
}
