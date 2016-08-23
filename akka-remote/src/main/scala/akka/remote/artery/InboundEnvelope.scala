/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.InternalActorRef
import akka.util.OptionVal
import akka.actor.Address
import akka.actor.ActorRef

/**
 * INTERNAL API
 */
private[akka] object InboundEnvelope {
  def apply(
    recipient:        OptionVal[InternalActorRef],
    recipientAddress: Address,
    message:          AnyRef,
    sender:           OptionVal[ActorRef],
    originUid:        Long,
    association:      OptionVal[OutboundContext]): InboundEnvelope = {
    val env = new ReusableInboundEnvelope
    env.init(recipient, recipientAddress, message, sender, originUid, association)
  }

}

/**
 * INTERNAL API
 */
private[akka] trait InboundEnvelope {
  def recipient: OptionVal[InternalActorRef]
  def recipientAddress: Address
  def message: AnyRef
  def sender: OptionVal[ActorRef]
  def originUid: Long
  def association: OptionVal[OutboundContext]

  def withMessage(message: AnyRef): InboundEnvelope

  def withRecipient(ref: InternalActorRef): InboundEnvelope
}

/**
 * INTERNAL API
 */
private[akka] object ReusableInboundEnvelope {
  def createObjectPool(capacity: Int) = new ObjectPool[ReusableInboundEnvelope](
    capacity,
    create = () ⇒ new ReusableInboundEnvelope, clear = inEnvelope ⇒ inEnvelope.asInstanceOf[ReusableInboundEnvelope].clear())
}

/**
 * INTERNAL API
 */
private[akka] final class ReusableInboundEnvelope extends InboundEnvelope {
  private var _recipient: OptionVal[InternalActorRef] = OptionVal.None
  private var _recipientAddress: Address = null
  private var _message: AnyRef = null
  private var _sender: OptionVal[ActorRef] = OptionVal.None
  private var _originUid: Long = 0L
  private var _association: OptionVal[OutboundContext] = OptionVal.None

  override def recipient: OptionVal[InternalActorRef] = _recipient
  override def recipientAddress: Address = _recipientAddress
  override def message: AnyRef = _message
  override def sender: OptionVal[ActorRef] = _sender
  override def originUid: Long = _originUid
  override def association: OptionVal[OutboundContext] = _association

  override def withMessage(message: AnyRef): InboundEnvelope = {
    _message = message
    this
  }

  def withRecipient(ref: InternalActorRef): InboundEnvelope = {
    _recipient = OptionVal(ref)
    this
  }

  def clear(): Unit = {
    _recipient = OptionVal.None
    _recipientAddress = null
    _message = null
    _sender = OptionVal.None
    _originUid = 0L
    _association = OptionVal.None
  }

  def init(
    recipient:        OptionVal[InternalActorRef],
    recipientAddress: Address,
    message:          AnyRef,
    sender:           OptionVal[ActorRef],
    originUid:        Long,
    association:      OptionVal[OutboundContext]): InboundEnvelope = {
    _recipient = recipient
    _recipientAddress = recipientAddress
    _message = message
    _sender = sender
    _originUid = originUid
    _association = association
    this
  }

  override def toString: String =
    s"InboundEnvelope($recipient, $recipientAddress, $message, $sender, $originUid, $association)"
}
