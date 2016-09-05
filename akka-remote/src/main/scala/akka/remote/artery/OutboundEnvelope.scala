/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.InternalActorRef
import akka.util.{ ByteString, OptionVal }
import akka.actor.Address
import akka.actor.ActorRef
import akka.remote.RemoteActorRef

import scala.annotation.varargs

/**
 * INTERNAL API
 */
private[akka] object OutboundEnvelope {
  def apply(
    recipient: OptionVal[RemoteActorRef],
    message:   AnyRef,
    sender:    OptionVal[ActorRef]): OutboundEnvelope = {
    val env = new ReusableOutboundEnvelope
    env.init(recipient, message, sender)
  }

}

/**
 * INTERNAL API
 */
private[akka] trait OutboundEnvelope {
  def recipient: OptionVal[RemoteActorRef]
  def message: AnyRef
  def sender: OptionVal[ActorRef]
  def metadata: OptionVal[Map[Byte, ByteString]]

  def withMessage(message: AnyRef): OutboundEnvelope
  def withMetadata(metadata: Map[Byte, ByteString]): OutboundEnvelope

  def copy(): OutboundEnvelope
}

/**
 * INTERNAL API
 */
private[akka] object ReusableOutboundEnvelope {
  def createObjectPool(capacity: Int) = new ObjectPool[ReusableOutboundEnvelope](
    capacity,
    create = () ⇒ new ReusableOutboundEnvelope, clear = outEnvelope ⇒ outEnvelope.clear())
}

/**
 * INTERNAL API
 */
private[akka] final class ReusableOutboundEnvelope extends OutboundEnvelope {
  private var _recipient: OptionVal[RemoteActorRef] = OptionVal.None
  private var _message: AnyRef = null
  private var _sender: OptionVal[ActorRef] = OptionVal.None
  private var _metadata: OptionVal[Map[Byte, ByteString]] = OptionVal.None

  override def recipient: OptionVal[RemoteActorRef] = _recipient
  override def message: AnyRef = _message
  override def sender: OptionVal[ActorRef] = _sender
  override def metadata: OptionVal[Map[Byte, ByteString]] = _metadata

  override def withMessage(message: AnyRef): OutboundEnvelope = {
    _message = message
    this
  }

  /**
   * IMPORTANT: The Byte keys must be greater than 0 and less than 32.
   * These are refered to as metadata-slot keys. They should be statically assigned
   * on a tool-by-tool basis.
   *
   * The keys 0–7 are reserved for Akka internal purposes and future extensions.
   */
  def withMetadata(metadata: Map[Byte, ByteString]): OutboundEnvelope = {
    _metadata = OptionVal.Some(metadata)
    this
  }

  def copy(): OutboundEnvelope =
    (new ReusableOutboundEnvelope).init(_recipient, _message, _sender)

  def clear(): Unit = {
    _recipient = OptionVal.None
    _message = null
    _sender = OptionVal.None
  }

  def init(
    recipient: OptionVal[RemoteActorRef],
    message:   AnyRef,
    sender:    OptionVal[ActorRef]): OutboundEnvelope = {
    _recipient = recipient
    _message = message
    _sender = sender
    this
  }

  override def toString: String =
    s"OutboundEnvelope($recipient, $message, $sender)"
}
