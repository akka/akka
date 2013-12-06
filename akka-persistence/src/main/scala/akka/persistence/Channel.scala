/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.collection.immutable
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor._
import akka.dispatch.Envelope

import akka.persistence.JournalProtocol.Confirm
import akka.persistence.serialization.Message

/**
 * A [[Channel]] configuration object.
 *
 * @param redeliverMax maximum number of redeliveries (default is 5).
 * @param redeliverInterval interval between redeliveries (default is 5 seconds).
 */
@SerialVersionUID(1L)
class ChannelSettings(
  val redeliverMax: Int,
  val redeliverInterval: FiniteDuration) extends Serializable {

  /**
   * Java API.
   */
  def withRedeliverMax(redeliverMax: Int): ChannelSettings =
    update(redeliverMax = redeliverMax)

  /**
   * Java API.
   */
  def withRedeliverInterval(redeliverInterval: FiniteDuration): ChannelSettings =
    update(redeliverInterval = redeliverInterval)

  private def update(
    redeliverMax: Int = redeliverMax,
    redeliverInterval: FiniteDuration = redeliverInterval): ChannelSettings =
    new ChannelSettings(redeliverMax, redeliverInterval)
}

object ChannelSettings {
  def apply(
    redeliverMax: Int = 5,
    redeliverInterval: FiniteDuration = 5 seconds): ChannelSettings =
    new ChannelSettings(redeliverMax, redeliverInterval)

  /**
   * Java API.
   */
  def create() = apply()
}

/**
 * A channel is used by [[Processor]]s for sending [[Persistent]] messages to destinations. The main
 * responsibility of a channel is to prevent redundant delivery of replayed messages to destinations
 * when a processor is recovered.
 *
 * A channel is instructed to deliver a persistent message to a `destination` with the [[Deliver]]
 * command.
 *
 * {{{
 * class ForwardExample extends Processor {
 *   val destination = context.actorOf(Props[MyDestination])
 *   val channel = context.actorOf(Channel.props(), "myChannel")
 *
 *   def receive = {
 *     case m @ Persistent(payload, _) =>
 *       // forward modified message to destination
 *       channel forward Deliver(m.withPayload(s"fw: ${payload}"), destination)
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
 *       channel ! Deliver(m.withPayload(s"re: ${payload}"), sender)
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
 * by the channel according to the parameters in [[ChannelSettings]]. Message redelivery is done out of order
 * with regards to normal delivery i.e. redelivered messages may arrive later than newer normally delivered
 * messages. Redelivered messages have a `redeliveries` value greater than zero.
 *
 * If the maximum number of redeliveries for a certain message is reached and there is still no confirmation
 * from the destination, then this message is removed from the channel. In order to deliver that message to
 * the destination again, the processor must replay its stored messages to the channel (during start or restart).
 * Replayed, unconfirmed messages are then processed and delivered by the channel again. These messages are now
 * duplicates (with a `redeliveries` counter starting from zero). Duplicates can be detected by destinations
 * by tracking message sequence numbers.
 *
 * @see [[Deliver]]
 */
final class Channel private[akka] (_channelId: Option[String], channelSettings: ChannelSettings) extends Actor {
  private val id = _channelId match {
    case Some(cid) ⇒ cid
    case None      ⇒ Persistence(context.system).channelId(self)
  }

  private val journal = Persistence(context.system).journalFor(id)

  private val reliableDelivery = context.actorOf(Props(classOf[ReliableDelivery], channelSettings))
  private val resolvedDelivery = context.actorOf(Props(classOf[ResolvedDelivery], reliableDelivery))

  def receive = {
    case d @ Deliver(persistent: PersistentRepr, _, _) ⇒
      if (!persistent.confirms.contains(id)) resolvedDelivery forward d.copy(prepareDelivery(persistent))
  }

  private def prepareDelivery(persistent: PersistentRepr): PersistentRepr =
    ConfirmablePersistentImpl(persistent,
      confirmTarget = journal,
      confirmMessage = Confirm(persistent.processorId, persistent.sequenceNr, id))
}

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
 * Instructs a [[Channel]] or [[PersistentChannel]] to deliver `persistent` message to
 * destination `destination`. The `resolve` parameter can be:
 *
 *  - `Resolve.Destination`: will resolve a new destination reference from the specified
 *    `destination`s path. The `persistent` message will be sent to the newly resolved
 *    destination.
 *  - `Resolve.Sender`: will resolve a new sender reference from this `Deliver` message's
 *    `sender` path. The `persistent` message will be sent to the specified `destination`
 *    using the newly resolved sender.
 *  - `Resolve.Off`: will not do any resolution (default).
 *
 * Resolving an actor reference means first obtaining an `ActorSelection` from the path of
 * the reference to be resolved and then obtaining a new actor reference via an `Identify`
 * - `ActorIdentity` conversation. Actor reference resolution does not change the original
 * order of messages.
 *
 * Resolving actor references may become necessary when using the stored sender references
 * of replayed messages. A stored sender reference may become invalid (for example, it may
 * reference a previous sender incarnation, after a JVM restart). Depending on how a processor
 * uses sender references, two resolution strategies are relevant.
 *
 *  - `Resolve.Sender` when a processor forwards a replayed message to a destination.
 *
 * {{{
 *   channel forward Deliver(message, destination, Resolve.Sender)
 * }}}
 *
 *  - `Resolve.Destination` when a processor replies to the sender of a replayed message. In
 *    this case the sender is used as channel destination.
 *
 * {{{
 *   channel ! Deliver(message, sender, Resolve.Destination)
 * }}}
 *
 * A destination or sender reference will only be resolved by a channel if
 *
 *  - the `resolve` parameter is set to `Resolve.Destination` or `Resolve.Channel`
 *  - the message is replayed
 *  - the message is not retained by the channel and
 *  - there was no previous successful resolve action for that message
 *
 * @param persistent persistent message.
 * @param destination persistent message destination.
 * @param resolve resolve strategy.
 */
@SerialVersionUID(1L)
case class Deliver(persistent: Persistent, destination: ActorRef, resolve: Resolve.ResolveStrategy = Resolve.Off) extends Message

object Deliver {
  /**
   * Java API.
   */
  def create(persistent: Persistent, destination: ActorRef) = Deliver(persistent, destination)

  /**
   * Java API.
   */
  def create(persistent: Persistent, destination: ActorRef, resolve: Resolve.ResolveStrategy) = Deliver(persistent, destination, resolve)
}

/**
 * Actor reference resolution strategy.
 *
 * @see [[Deliver]]
 */
object Resolve {
  sealed abstract class ResolveStrategy

  /**
   * No resolution.
   */
  @SerialVersionUID(1L)
  case object Off extends ResolveStrategy

  /**
   * [[Channel]] should resolve the `sender` of a [[Deliver]] message.
   */
  @SerialVersionUID(1L)
  case object Sender extends ResolveStrategy

  /**
   * [[Channel]] should resolve the `destination` of a [[Deliver]] message.
   */
  @SerialVersionUID(1L)
  case object Destination extends ResolveStrategy

  /**
   * Java API.
   */
  def off() = Off

  /**
   * Java API.
   */
  def sender() = Sender

  /**
   * Java API.
   */
  def destination() = Destination
}

/**
 * Resolves actor references as specified by [[Deliver]] requests and then delegates delivery
 * to `next`.
 */
private class ResolvedDelivery(next: ActorRef) extends Actor with Stash {
  private var currentResolution: Envelope = _

  private val delivering: Receive = {
    case d @ Deliver(persistent: PersistentRepr, destination, resolve) ⇒
      resolve match {
        case Resolve.Sender if !persistent.resolved ⇒
          context.actorSelection(sender.path) ! Identify(1)
          context.become(resolving, discardOld = false)
          currentResolution = Envelope(d, sender, context.system)
        case Resolve.Destination if !persistent.resolved ⇒
          context.actorSelection(destination.path) ! Identify(1)
          context.become(resolving, discardOld = false)
          currentResolution = Envelope(d, sender, context.system)
        case _ ⇒ next forward d
      }
      unstash()
  }

  private val resolving: Receive = {
    case ActorIdentity(1, resolvedOption) ⇒
      val Envelope(d: Deliver, sender) = currentResolution
      if (d.resolve == Resolve.Sender) {
        next tell (d, resolvedOption.getOrElse(sender))
      } else if (d.resolve == Resolve.Destination) {
        next tell (d.copy(destination = resolvedOption.getOrElse(d.destination)), sender)
      }
      context.unbecome()
      unstash()
    case _: Deliver ⇒ stash()
  }

  def receive = delivering
}

/**
 * Reliably deliver messages contained in [[Deliver]] requests to their destinations. Unconfirmed
 * messages are redelivered according to the parameters in [[ChannelSettings]].
 */
private class ReliableDelivery(channelSettings: ChannelSettings) extends Actor {
  import channelSettings._
  import ReliableDelivery._

  private val redelivery = context.actorOf(Props(classOf[Redelivery], channelSettings))
  private var attempts: DeliveryAttempts = Map.empty
  private var sequenceNr: Long = 0L

  def receive = {
    case d @ Deliver(persistent: PersistentRepr, destination, _) ⇒
      val dsnr = nextSequenceNr()
      val psnr = persistent.sequenceNr
      val confirm = persistent.confirmMessage.copy(channelEndpoint = self)
      val updated = persistent.update(confirmMessage = confirm, sequenceNr = if (psnr == 0) dsnr else psnr)
      destination forward updated
      attempts += ((updated.processorId, updated.sequenceNr) -> DeliveryAttempt(updated, destination, sender, dsnr))
    case c @ Confirm(processorId, messageSequenceNr, _, _, _) ⇒
      attempts -= ((processorId, messageSequenceNr))
    case Redeliver ⇒
      val limit = System.nanoTime - redeliverInterval.toNanos
      val (older, younger) = attempts.partition { case (_, a) ⇒ a.timestamp < limit }
      redelivery ! Redeliver(older, redeliverMax)
      attempts = younger
  }

  private def nextSequenceNr(): Long = {
    sequenceNr += 1
    sequenceNr
  }
}

private object ReliableDelivery {
  type DeliveryAttempts = immutable.Map[(String, Long), DeliveryAttempt]

  case class DeliveryAttempt(persistent: PersistentRepr, destination: ActorRef, sender: ActorRef, deliverySequenceNr: Long, timestamp: Long = System.nanoTime) {
    def withChannelEndpoint(channelEndpoint: ActorRef) =
      copy(persistent.update(confirmMessage = persistent.confirmMessage.copy(channelEndpoint = channelEndpoint)))

    def incrementRedeliveryCount =
      copy(persistent.update(redeliveries = persistent.redeliveries + 1))
  }

  case class Redeliver(attempts: DeliveryAttempts, redeliveryMax: Int)
}

/**
 * Redelivery process used by [[ReliableDelivery]].
 */
private class Redelivery(channelSettings: ChannelSettings) extends Actor {
  import context.dispatcher
  import channelSettings._
  import ReliableDelivery._

  private var attempts: DeliveryAttempts = Map.empty
  private var schedule: Cancellable = _

  def receive = {
    case Redeliver(as, max) ⇒
      attempts ++= as.map { case (k, a) ⇒ (k, a.withChannelEndpoint(self)) }
      attempts = attempts.foldLeft[DeliveryAttempts](Map.empty) {
        case (acc, (k, attempt)) ⇒
          // drop redelivery attempts that exceed redeliveryMax
          if (attempt.persistent.redeliveries >= redeliverMax) acc
          // increase redelivery count of attempt
          else acc + (k -> attempt.incrementRedeliveryCount)
      }
      redeliver(attempts)
      scheduleRedelivery()
    case c @ Confirm(processorId, messageSequenceNr, _, _, _) ⇒
      attempts -= ((processorId, messageSequenceNr))
  }

  override def preStart(): Unit =
    scheduleRedelivery()

  override def postStop(): Unit =
    schedule.cancel()

  private def scheduleRedelivery(): Unit =
    schedule = context.system.scheduler.scheduleOnce(redeliverInterval, context.parent, Redeliver)

  private def redeliver(attempts: DeliveryAttempts): Unit =
    attempts.values.toSeq.sortBy(_.deliverySequenceNr).foreach(ad ⇒ ad.destination tell (ad.persistent, ad.sender))
}

