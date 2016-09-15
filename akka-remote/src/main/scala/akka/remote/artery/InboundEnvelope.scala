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
private[remote] object InboundEnvelope {
  /**
   * Only used in tests
   */
  def apply(
    recipient:        OptionVal[InternalActorRef],
    recipientAddress: Address,
    message:          AnyRef,
    sender:           OptionVal[ActorRef],
    originUid:        Long,
    association:      OptionVal[OutboundContext]): InboundEnvelope = {
    val env = new ReusableInboundEnvelope
    env.init(recipient, recipientAddress, sender, originUid, -1, "", null, association)
      .withMessage(message)
  }

}

/**
 * INTERNAL API
 */
private[remote] trait InboundEnvelope {
  def recipient: OptionVal[InternalActorRef]
  def recipientAddress: Address
  def sender: OptionVal[ActorRef]
  def originUid: Long
  def association: OptionVal[OutboundContext]

  def serializer: Int
  def classManifest: String
  def message: AnyRef
  def envelopeBuffer: EnvelopeBuffer

  def withMessage(message: AnyRef): InboundEnvelope

  def releaseEnvelopeBuffer(): InboundEnvelope

  def withRecipient(ref: InternalActorRef): InboundEnvelope
}

/**
 * INTERNAL API
 */
private[remote] object ReusableInboundEnvelope {
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
  private var _sender: OptionVal[ActorRef] = OptionVal.None
  private var _originUid: Long = 0L
  private var _association: OptionVal[OutboundContext] = OptionVal.None
  private var _serializer: Int = -1
  private var _classManifest: String = null
  private var _message: AnyRef = null
  private var _envelopeBuffer: EnvelopeBuffer = null

  override def recipient: OptionVal[InternalActorRef] = _recipient
  override def recipientAddress: Address = _recipientAddress
  override def sender: OptionVal[ActorRef] = _sender
  override def originUid: Long = _originUid
  override def association: OptionVal[OutboundContext] = _association
  override def serializer: Int = _serializer
  override def classManifest: String = _classManifest
  override def message: AnyRef = _message
  override def envelopeBuffer: EnvelopeBuffer = _envelopeBuffer

  override def withMessage(message: AnyRef): InboundEnvelope = {
    _message = message
    this
  }

  def releaseEnvelopeBuffer(): InboundEnvelope = {
    _envelopeBuffer = null
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
    sender:           OptionVal[ActorRef],
    originUid:        Long,
    serializer:       Int,
    classManifest:    String,
    envelopeBuffer:   EnvelopeBuffer,
    association:      OptionVal[OutboundContext]): InboundEnvelope = {
    _recipient = recipient
    _recipientAddress = recipientAddress
    _sender = sender
    _originUid = originUid
    _serializer = serializer
    _classManifest = classManifest
    _envelopeBuffer = envelopeBuffer
    _association = association
    this
  }

  override def toString: String =
    s"InboundEnvelope($recipient, $recipientAddress, $message, $sender, $originUid, $association)"
}
