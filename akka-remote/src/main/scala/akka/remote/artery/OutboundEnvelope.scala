/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.ActorRef
import akka.remote.RemoteActorRef
import akka.util.OptionVal

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

  def withMessage(message: AnyRef): OutboundEnvelope

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

  override def recipient: OptionVal[RemoteActorRef] = _recipient
  override def message: AnyRef = _message
  override def sender: OptionVal[ActorRef] = _sender

  override def withMessage(message: AnyRef): OutboundEnvelope = {
    _message = message
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
