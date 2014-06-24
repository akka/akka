/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import java.lang.{ Iterable ⇒ JIterable }

import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor._

import akka.persistence.serialization.Message
import akka.persistence.JournalProtocol._

// TODO: remove Channel

/**
 * A [[Channel]] configuration object.
 *
 * @param redeliverMax Maximum number of redelivery attempts.
 * @param redeliverInterval Interval between redelivery attempts.
 * @param redeliverFailureListener Receiver of [[RedeliverFailure]] notifications which are sent when the number
 *                                 of redeliveries reaches `redeliverMax` for a sequence of messages. To enforce
 *                                 a redelivery of these messages, the listener has to restart the sending processor.
 *                                 Alternatively, it can also confirm these messages, preventing further redeliveries.
 */
@SerialVersionUID(1L)
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
final case class ChannelSettings(
  val redeliverMax: Int = 5,
  val redeliverInterval: FiniteDuration = 5.seconds,
  val redeliverFailureListener: Option[ActorRef] = None) {

  /**
   * Java API.
   */
  def withRedeliverMax(redeliverMax: Int): ChannelSettings =
    copy(redeliverMax = redeliverMax)

  /**
   * Java API.
   */
  def withRedeliverInterval(redeliverInterval: FiniteDuration): ChannelSettings =
    copy(redeliverInterval = redeliverInterval)

  /**
   * Java API.
   */
  def withRedeliverFailureListener(redeliverFailureListener: ActorRef): ChannelSettings =
    copy(redeliverFailureListener = Option(redeliverFailureListener))
}

@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
object ChannelSettings {
  /**
   * Java API.
   */
  def create() = ChannelSettings.apply()
}

/**
 * A channel is used by [[Processor]]s (and [[View]]s) for sending [[Persistent]] messages to destinations.
 * The main responsibility of a channel is to prevent redundant delivery of replayed messages to destinations
 * when a processor is recovered.
 *
 * A channel is instructed to deliver a persistent message to a destination with the [[Deliver]] command. A
 * destination is provided as `ActorPath` and messages are sent via that path's `ActorSelection`.
 *
 * {{{
 * class ForwardExample extends Processor {
 *   val destination = context.actorOf(Props[MyDestination])
 *   val channel = context.actorOf(Channel.props(), "myChannel")
 *
 *   def receive = {
 *     case m @ Persistent(payload, _) =>
 *       // forward modified message to destination
 *       channel forward Deliver(m.withPayload(s"fw: ${payload}"), destination.path)
 *   }
 * }
 * }}}
 *
 * To reply to the sender of a persistent message, the `sender` reference should be used as channel
 * destination.
 *
 * {{{
 * class ReplyExample extends Processor {
 *   val channel = context.actorOf(Channel.props(), "myChannel")
 *
 *   def receive = {
 *     case m @ Persistent(payload, _) =>
 *       // reply modified message to sender
 *       channel ! Deliver(m.withPayload(s"re: ${payload}"), sender.path)
 *   }
 * }
 * }}}
 *
 * Redundant delivery of messages to destinations is only prevented if the receipt of these messages
 * is explicitly confirmed. Therefore, persistent messages that are delivered via a channel are of type
 * [[ConfirmablePersistent]]. Their receipt can be confirmed by a destination by calling the `confirm()`
 * method on these messages.
 *
 * {{{
 * class MyDestination extends Actor {
 *   def receive = {
 *     case cp @ ConfirmablePersistent(payload, sequenceNr, redeliveries) => cp.confirm()
 *   }
 * }
 * }}}
 *
 * If a destination does not confirm the receipt of a `ConfirmablePersistent` message, it will be redelivered
 * by the channel according to the parameters in [[ChannelSettings]]. Redelivered messages have a `redeliveries`
 * value greater than zero.
 *
 * If the maximum number of redeliveries is reached for certain messages, they are removed from the channel and
 * a `redeliverFailureListener` (if specified, see [[ChannelSettings]]) is notified about these messages with a
 * [[RedeliverFailure]] message. Besides other application-specific tasks, this listener can restart the sending
 * processor to enforce a redelivery of these messages or confirm these messages to prevent further redeliveries.
 *
 * @see [[Deliver]]
 */
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
final class Channel private[akka] (_channelId: Option[String], channelSettings: ChannelSettings) extends Actor {
  import channelSettings._

  private val id = _channelId match {
    case Some(cid) ⇒ cid
    case None      ⇒ Persistence(context.system).channelId(self)
  }

  private val journal = Persistence(context.system).confirmationBatchingJournalForChannel(id)
  private val delivery = context.actorOf(Props(classOf[ReliableDelivery], channelSettings))

  def receive = {
    case d @ Deliver(persistent: PersistentRepr, _) ⇒
      if (!persistent.confirms.contains(id)) delivery forward d.copy(prepareDelivery(persistent))
    case d: RedeliverFailure ⇒ redeliverFailureListener.foreach(_ ! d)
    case d: Delivered        ⇒ delivery forward d
  }

  private def prepareDelivery(persistent: PersistentRepr): PersistentRepr =
    ConfirmablePersistentImpl(persistent,
      confirmTarget = journal,
      confirmMessage = DeliveredByChannel(persistent.persistenceId, id, persistent.sequenceNr, channel = self))
}

@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
object Channel {
  /**
   * Returns a channel actor configuration object for creating a [[Channel]] with a
   * generated id and default [[ChannelSettings]].
   */
  def props(): Props =
    props(ChannelSettings())

  /**
   * Returns a channel actor configuration object for creating a [[Channel]] with a
   * generated id and specified `channelSettings`.
   *
   * @param channelSettings channel configuration object.
   */
  def props(channelSettings: ChannelSettings): Props =
    Props(classOf[Channel], None, channelSettings)

  /**
   * Returns a channel actor configuration object for creating a [[Channel]] with the
   * specified id and default [[ChannelSettings]].
   *
   * @param channelId channel id.
   */
  def props(channelId: String): Props =
    props(channelId, ChannelSettings())

  /**
   * Returns a channel actor configuration object for creating a [[Channel]] with the
   * specified id  and specified `channelSettings`.
   *
   * @param channelId channel id.
   * @param channelSettings channel configuration object.
   */
  def props(channelId: String, channelSettings: ChannelSettings): Props =
    Props(classOf[Channel], Some(channelId), channelSettings)
}

/**
 * Instructs a [[Channel]] or [[PersistentChannel]] to deliver a `persistent` message to
 * a `destination`.
 *
 * @param persistent persistent message.
 * @param destination persistent message destination.
 */
@SerialVersionUID(1L)
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
final case class Deliver(persistent: Persistent, destination: ActorPath) extends Message

@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
object Deliver {
  /**
   * Java API.
   */
  def create(persistent: Persistent, destination: ActorPath) = Deliver(persistent, destination)
}

/**
 * Plugin API: confirmation message generated by receivers of [[ConfirmablePersistent]] messages
 * by calling `ConfirmablePersistent.confirm()`.
 */
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
trait Delivered extends Message {
  def channelId: String
  def persistentSequenceNr: Long
  def deliverySequenceNr: Long
  def channel: ActorRef

  /**
   * INTERNAL API.
   */
  private[persistence] def update(deliverySequenceNr: Long = deliverySequenceNr, channel: ActorRef = channel): Delivered
}

/**
 * Plugin API.
 */
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
final case class DeliveredByChannel(
  @deprecatedName('processorId) persistenceId: String,
  channelId: String,
  persistentSequenceNr: Long,
  deliverySequenceNr: Long = 0L,
  channel: ActorRef = null) extends Delivered with PersistentConfirmation {

  def sequenceNr: Long = persistentSequenceNr
  def update(deliverySequenceNr: Long, channel: ActorRef): DeliveredByChannel =
    copy(deliverySequenceNr = deliverySequenceNr, channel = channel)
}

/**
 * INTERNAL API.
 */
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
private[persistence] class DeliveredByChannelBatching(journal: ActorRef, settings: PersistenceSettings) extends Actor {
  private val publish = settings.internal.publishConfirmations
  private val batchMax = settings.journal.maxConfirmationBatchSize

  private var batching = false
  private var batch = Vector.empty[DeliveredByChannel]

  def receive = {
    case WriteConfirmationsSuccess(confirmations) ⇒
      if (batch.isEmpty) batching = false else journalBatch()
      confirmations.foreach { c ⇒
        val dbc = c.asInstanceOf[DeliveredByChannel]
        if (dbc.channel != null) dbc.channel ! c
        if (publish) context.system.eventStream.publish(c)
      }
    case WriteConfirmationsFailure(_) ⇒
      if (batch.isEmpty) batching = false else journalBatch()
    case d: DeliveredByChannel ⇒
      addToBatch(d)
      if (!batching || maxBatchSizeReached) journalBatch()
    case m ⇒ journal forward m
  }

  def addToBatch(pc: DeliveredByChannel): Unit =
    batch = batch :+ pc

  def maxBatchSizeReached: Boolean =
    batch.length >= batchMax

  def journalBatch(): Unit = {
    journal ! WriteConfirmations(batch, self)
    batch = Vector.empty
    batching = true
  }
}

/**
 * Notification message to inform channel listeners about messages that have reached the maximum
 * number of redeliveries.
 */
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
final case class RedeliverFailure(messages: immutable.Seq[ConfirmablePersistent]) {
  /**
   * Java API.
   */
  def getMessages: JIterable[ConfirmablePersistent] = messages.asJava
}

/**
 * Reliably deliver messages contained in [[Deliver]] requests to their destinations. Unconfirmed
 * messages are redelivered according to the parameters in [[ChannelSettings]].
 */
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
private class ReliableDelivery(redeliverSettings: ChannelSettings) extends Actor {
  import redeliverSettings._
  import ReliableDelivery._

  private val redelivery = context.actorOf(Props(classOf[Redelivery], redeliverSettings))
  private var deliveryAttempts: DeliveryAttempts = immutable.SortedMap.empty
  private var deliverySequenceNr: Long = 0L

  def receive = {
    case d @ Deliver(persistent: ConfirmablePersistentImpl, destination) ⇒
      val dsnr = nextDeliverySequenceNr()
      val psnr = persistent.sequenceNr
      val confirm = persistent.confirmMessage.update(deliverySequenceNr = dsnr)
      val updated = persistent.update(confirmMessage = confirm, sequenceNr = if (psnr == 0) dsnr else psnr)
      context.actorSelection(destination).tell(updated, sender())
      deliveryAttempts += (dsnr -> DeliveryAttempt(updated, destination, sender()))
    case d: Delivered ⇒
      deliveryAttempts -= d.deliverySequenceNr
      redelivery forward d
    case Redeliver ⇒
      val limit = System.nanoTime - redeliverInterval.toNanos
      val (older, younger) = deliveryAttempts.span { case (_, a) ⇒ a.timestamp < limit }
      redelivery ! Redeliver(older, redeliverMax)
      deliveryAttempts = younger
  }

  private def nextDeliverySequenceNr(): Long = {
    deliverySequenceNr += 1
    deliverySequenceNr
  }
}

@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
private object ReliableDelivery {
  type DeliveryAttempts = immutable.SortedMap[Long, DeliveryAttempt]
  type FailedAttempts = Vector[ConfirmablePersistentImpl]

  final case class DeliveryAttempt(persistent: ConfirmablePersistentImpl, destination: ActorPath, sender: ActorRef, timestamp: Long = System.nanoTime) {
    def incrementRedeliveryCount =
      copy(persistent.update(redeliveries = persistent.redeliveries + 1))
  }

  final case class Redeliver(attempts: DeliveryAttempts, redeliveryMax: Int)
}

/**
 * Redelivery process used by [[ReliableDelivery]].
 */
@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
private class Redelivery(redeliverSettings: ChannelSettings) extends Actor {
  import context.dispatcher
  import redeliverSettings._
  import ReliableDelivery._

  private var redeliveryAttempts: DeliveryAttempts = immutable.SortedMap.empty
  private var redeliverySchedule: Cancellable = _

  def receive = {
    case Redeliver(as, max) ⇒
      val (attempts, failed) = (redeliveryAttempts ++ as).foldLeft[(DeliveryAttempts, FailedAttempts)]((immutable.SortedMap.empty, Vector.empty)) {
        case ((attempts, failed), (k, attempt)) ⇒
          val persistent = attempt.persistent
          if (persistent.redeliveries >= redeliverMax) {
            (attempts, failed :+ persistent)
          } else {
            val updated = attempt.incrementRedeliveryCount
            context.actorSelection(updated.destination).tell(updated.persistent, updated.sender)
            (attempts.updated(k, updated), failed)

          }
      }
      redeliveryAttempts = attempts
      scheduleRedelivery()
      failed.headOption.foreach(_.confirmMessage.channel ! RedeliverFailure(failed))
    case c: Delivered ⇒
      redeliveryAttempts -= c.deliverySequenceNr
  }

  override def preStart(): Unit =
    scheduleRedelivery()

  override def postStop(): Unit =
    redeliverySchedule.cancel()

  private def scheduleRedelivery(): Unit =
    redeliverySchedule = context.system.scheduler.scheduleOnce(redeliverInterval, context.parent, Redeliver)
}

