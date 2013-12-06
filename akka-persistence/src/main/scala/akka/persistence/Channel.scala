/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.AkkaException
import akka.actor._

import akka.persistence.serialization.Message

/**
 * A channel is used by [[Processor]]s for sending [[Persistent]] messages to destinations. The main
 * responsibility of a channel is to prevent redundant delivery of replayed messages to destinations
 * when a processor is recovered.
 *
 * A channel can be instructed to deliver a persistent message to a `destination` via the [[Deliver]]
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
 *     case cp @ ConfirmablePersistent(payload, sequenceNr) => cp.confirm()
 *   }
 * }
 * }}}
 *
 * A channel will only re-deliver messages if the sending processor is recovered and delivery of these
 * messages has not been confirmed yet. Hence, a channel can be used to avoid message loss in case of
 * sender JVM crashes, for example. A channel, however, does not attempt any re-deliveries should a
 * destination be unavailable. Re-delivery to destinations (in case of network failures or destination
 * JVM crashes) is an application-level concern and can be done by using a reliable proxy, for example.
 *
 * @see [[Deliver]]
 */
sealed class Channel private[akka] (_channelId: Option[String]) extends Actor with Stash {
  private val extension = Persistence(context.system)
  private val id = _channelId match {
    case Some(cid) ⇒ cid
    case None      ⇒ extension.channelId(self)
  }

  import ResolvedDelivery._

  private val delivering: Actor.Receive = {
    case Deliver(persistent: PersistentRepr, destination, resolve) ⇒
      if (!persistent.confirms.contains(id)) {
        val prepared = prepareDelivery(persistent)
        resolve match {
          case Resolve.Sender if !prepared.resolved ⇒
            context.actorOf(Props(classOf[ResolvedSenderDelivery], prepared, destination, sender)) ! DeliverResolved
            context.become(buffering, false)
          case Resolve.Destination if !prepared.resolved ⇒
            context.actorOf(Props(classOf[ResolvedDestinationDelivery], prepared, destination, sender)) ! DeliverResolved
            context.become(buffering, false)
          case _ ⇒ destination tell (prepared, sender)
        }
      }
      unstash()
  }

  private val buffering: Actor.Receive = {
    case DeliveredResolved | DeliveredUnresolved ⇒
      context.unbecome()
      unstash()
    case _: Deliver ⇒ stash()
  }

  def receive = delivering

  private[akka] def prepareDelivery(persistent: PersistentRepr): PersistentRepr = {
    ConfirmablePersistentImpl(
      persistent = persistent,
      confirmTarget = extension.journalFor(persistent.processorId),
      confirmMessage = Confirm(persistent.processorId, persistent.sequenceNr, id))
  }
}

object Channel {
  /**
   * Returns a channel configuration object for creating a [[Channel]] with a
   * generated id.
   */
  def props(): Props = Props(classOf[Channel], None)

  /**
   * Returns a channel configuration object for creating a [[Channel]] with the
   * specified id.
   *
   * @param channelId channel id.
   */
  def props(channelId: String): Props = Props(classOf[Channel], Some(channelId))
}

/**
 * A [[PersistentChannel]] implements the same functionality as a [[Channel]] but additionally
 * persists messages before they are delivered. Therefore, the main use case of a persistent
 * channel is standalone usage i.e. independent of a sending [[Processor]]. Messages that have
 * been persisted by a persistent channel are deleted again when destinations confirm the receipt
 * of these messages.
 *
 * Using a persistent channel in combination with a [[Processor]] can make sense if destinations
 * are unavailable for a long time and an application doesn't want to buffer all messages in
 * memory (but write them to a journal instead). In this case, delivery can be disabled with
 * [[DisableDelivery]] (to stop delivery and persist-only) and re-enabled with [[EnableDelivery]].
 *
 * A persistent channel can also be configured to reply whether persisting a message was successful
 * or not (see `PersistentChannel.props` methods). If enabled, the sender will receive the persisted
 * message as reply (i.e. a [[Persistent]] message), otherwise a [[PersistenceFailure]] message.
 *
 * A persistent channel will only re-deliver un-confirmed, stored messages if it is started or re-
 * enabled with [[EnableDelivery]]. Hence, a persistent channel can be used to avoid message loss
 * in case of sender JVM crashes, for example. A channel, however, does not attempt any re-deliveries
 * should a destination be unavailable. Re-delivery to destinations (in case of network failures or
 * destination JVM crashes) is an application-level concern and can be done by using a reliable proxy,
 * for example.
 */
final class PersistentChannel private[akka] (_channelId: Option[String], persistentReply: Boolean) extends EventsourcedProcessor {
  override val processorId = _channelId.getOrElse(super.processorId)

  private val journal = Persistence(context.system).journalFor(processorId)
  private val channel = context.actorOf(Props(classOf[NoPrepChannel], processorId))

  private var deliveryEnabled = true

  def receiveReplay: Receive = {
    case Deliver(persistent: PersistentRepr, destination, resolve) ⇒ deliver(prepareDelivery(persistent), destination, resolve)
  }

  def receiveCommand: Receive = {
    case d @ Deliver(persistent: PersistentRepr, destination, resolve) ⇒
      if (!persistent.confirms.contains(processorId)) {
        persist(d) { _ ⇒
          val prepared = prepareDelivery(persistent)

          if (persistent.processorId != PersistentRepr.Undefined)
            journal ! Confirm(persistent.processorId, persistent.sequenceNr, processorId)

          if (persistentReply)
            sender ! prepared

          if (deliveryEnabled)
            deliver(prepared, destination, resolve)
        }
      }
    case c: Confirm                                 ⇒ deleteMessage(c.sequenceNr, true)
    case DisableDelivery                            ⇒ deliveryEnabled = false
    case EnableDelivery if (!deliveryEnabled)       ⇒ throw new ChannelRestartRequiredException
    case p: PersistenceFailure if (persistentReply) ⇒ sender ! p
  }

  private def prepareDelivery(persistent: PersistentRepr): PersistentRepr = currentPersistentMessage.map { current ⇒
    val sequenceNr = if (persistent.sequenceNr == 0L) current.sequenceNr else persistent.sequenceNr
    val resolved = persistent.resolved && current.asInstanceOf[PersistentRepr].resolved
    persistent.update(sequenceNr = sequenceNr, resolved = resolved)
  } getOrElse (persistent)

  private def deliver(persistent: PersistentRepr, destination: ActorRef, resolve: Resolve.ResolveStrategy) = currentPersistentMessage.foreach { current ⇒
    channel forward Deliver(persistent = ConfirmablePersistentImpl(persistent,
      confirmTarget = self,
      confirmMessage = Confirm(processorId, current.sequenceNr, PersistentRepr.Undefined)), destination, resolve)
  }
}

object PersistentChannel {
  /**
   * Returns a channel configuration object for creating a [[PersistentChannel]] with a
   * generated id. The sender will not receive persistence completion replies.
   */
  def props(): Props = props(persistentReply = false)

  /**
   * Returns a channel configuration object for creating a [[PersistentChannel]] with a
   * generated id.
   *
   * @param persistentReply if `true` the sender will receive the successfully stored
   *                        [[Persistent]] message that has been submitted with a
   *                        [[Deliver]] request, or a [[PersistenceFailure]] message
   *                        in case of a persistence failure.
   */
  def props(persistentReply: Boolean): Props = Props(classOf[PersistentChannel], None, persistentReply)

  /**
   * Returns a channel configuration object for creating a [[PersistentChannel]] with the
   * specified id. The sender will not receive persistence completion replies.
   *
   * @param channelId channel id.
   */
  def props(channelId: String): Props = props(channelId, persistentReply = false)

  /**
   * Returns a channel configuration object for creating a [[PersistentChannel]] with the
   * specified id.
   *
   * @param channelId channel id.
   * @param persistentReply if `true` the sender will receive the successfully stored
   *                        [[Persistent]] message that has been submitted with a
   *                        [[Deliver]] request, or a [[PersistenceFailure]] message
   *                        in case of a persistence failure.
   */
  def props(channelId: String, persistentReply: Boolean): Props = Props(classOf[PersistentChannel], Some(channelId), persistentReply)
}

/**
 * Instructs a [[PersistentChannel]] to disable the delivery of [[Persistent]] messages to their destination.
 * The persistent channel, however, continues to persist messages (for later delivery).
 *
 * @see [[EnableDelivery]]
 */
@SerialVersionUID(1L)
case object DisableDelivery {
  /**
   * Java API.
   */
  def getInstance = this
}

/**
 * Instructs a [[PersistentChannel]] to re-enable the delivery of [[Persistent]] messages to their destination.
 * This will first deliver all messages that have been stored by a persistent channel for which no confirmation
 * is available yet. New [[Deliver]] requests are processed after all stored messages have been delivered. This
 * request only has an effect if a persistent channel has previously been disabled with [[DisableDelivery]].
 *
 * @see [[DisableDelivery]]
 */
@SerialVersionUID(1L)
case object EnableDelivery {
  /**
   * Java API.
   */
  def getInstance = this
}

/**
 * Thrown by a persistent channel when [[EnableDelivery]] has been requested and delivery has been previously
 * disabled for that channel.
 */
@SerialVersionUID(1L)
class ChannelRestartRequiredException extends AkkaException("channel restart required for enabling delivery")

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
 * Resolved delivery support.
 */
private trait ResolvedDelivery extends Actor {
  import scala.concurrent.duration._
  import scala.language.postfixOps
  import ResolvedDelivery._

  context.setReceiveTimeout(5 seconds) // TODO: make configurable

  def path: ActorPath
  def onResolveSuccess(ref: ActorRef): Unit
  def onResolveFailure(): Unit

  def receive = {
    case DeliverResolved ⇒
      context.actorSelection(path) ! Identify(1)
    case ActorIdentity(1, Some(ref)) ⇒
      onResolveSuccess(ref)
      shutdown(DeliveredResolved)
    case ActorIdentity(1, None) ⇒
      onResolveFailure()
      shutdown(DeliveredUnresolved)
    case ReceiveTimeout ⇒
      onResolveFailure()
      shutdown(DeliveredUnresolved)
  }

  def shutdown(message: Any) {
    context.parent ! message
    context.stop(self)
  }
}

private object ResolvedDelivery {
  case object DeliverResolved
  case object DeliveredResolved
  case object DeliveredUnresolved
}

/**
 * Resolves `destination` before sending `persistent` message to the resolved destination using
 * the specified sender (`sdr`) as message sender.
 */
private class ResolvedDestinationDelivery(persistent: PersistentRepr, destination: ActorRef, sdr: ActorRef) extends ResolvedDelivery {
  val path = destination.path
  def onResolveSuccess(ref: ActorRef) = ref tell (persistent.update(resolved = true), sdr)
  def onResolveFailure() = destination tell (persistent, sdr)
}

/**
 * Resolves `sdr` before sending `persistent` message to specified `destination` using
 * the resolved sender as message sender.
 */
private class ResolvedSenderDelivery(persistent: PersistentRepr, destination: ActorRef, sdr: ActorRef) extends ResolvedDelivery {
  val path = sdr.path
  def onResolveSuccess(ref: ActorRef) = destination tell (persistent.update(resolved = true), ref)
  def onResolveFailure() = destination tell (persistent, sdr)
}

/**
 * [[Channel]] specialization used by [[PersistentChannel]] to deliver stored messages.
 */
private class NoPrepChannel(channelId: String) extends Channel(Some(channelId)) {
  override private[akka] def prepareDelivery(persistent: PersistentRepr) = persistent
}
