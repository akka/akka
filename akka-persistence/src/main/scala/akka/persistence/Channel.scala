/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._

/**
 * A channel is used by [[Processor]]s for sending received persistent messages to destinations.
 * It prevents redundant delivery of messages to these destinations when a processor is recovered
 * i.e. receives replayed messages. This requires that channel destinations confirm the receipt of
 * persistent messages by calling `confirm()` on the [[Persistent]] message.
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
 *     case m @ Persistent(payload, _) => {
 *       // forward modified message to destination
 *       channel forward Deliver(m.withPayload(s"fw: ${payload}"), destination)
 *     }
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
 *     case m @ Persistent(payload, _) => {
 *       // reply modified message to sender
 *       channel ! Deliver(m.withPayload(s"re: ${payload}"), sender)
 *     }
 *   }
 * }
 * }}}
 *
 * @see [[Deliver]]
 */
class Channel private (_channelId: Option[String]) extends Actor with Stash {
  private val extension = Persistence(context.system)
  private val id = _channelId match {
    case Some(cid) ⇒ cid
    case None      ⇒ extension.channelId(self)
  }

  /**
   * Creates a new channel with a generated channel id.
   */
  def this() = this(None)

  /**
   * Creates a new channel with specified channel id.
   *
   * @param channelId channel id.
   */
  def this(channelId: String) = this(Some(channelId))

  import ResolvedDelivery._

  private val delivering: Actor.Receive = {
    case Deliver(persistent: PersistentImpl, destination, resolve) ⇒ {
      if (!persistent.confirms.contains(id)) {
        val msg = persistent.copy(channelId = id,
          confirmTarget = extension.journalFor(persistent.processorId),
          confirmMessage = Confirm(persistent.processorId, persistent.sequenceNr, id))
        resolve match {
          case Resolve.Sender if !persistent.resolved ⇒ {
            context.actorOf(Props(classOf[ResolvedSenderDelivery], msg, destination, sender)) ! DeliverResolved
            context.become(buffering, false)
          }
          case Resolve.Destination if !persistent.resolved ⇒ {
            context.actorOf(Props(classOf[ResolvedDestinationDelivery], msg, destination, sender)) ! DeliverResolved
            context.become(buffering, false)
          }
          case _ ⇒ destination tell (msg, sender)
        }
      }
    }
  }

  private val buffering: Actor.Receive = {
    case DeliveredResolved | DeliveredUnresolved ⇒ { context.unbecome(); unstashAll() } // TODO: optimize
    case _: Deliver                              ⇒ stash()
  }

  def receive = delivering
}

object Channel {
  /**
   * Returns a channel configuration object for creating a [[Channel]] with a
   * generated id.
   */
  def props(): Props = Props(classOf[Channel])

  /**
   * Returns a channel configuration object for creating a [[Channel]] with the
   * specified id.
   *
   * @param channelId channel id.
   */
  def props(channelId: String): Props = Props(classOf[Channel], channelId)
}

/**
 * Instructs a [[Channel]] to deliver `persistent` message to destination `destination`.
 * The `resolve` parameter can be:
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
case class Deliver(persistent: Persistent, destination: ActorRef, resolve: Resolve.ResolveStrategy = Resolve.Off)

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
    case DeliverResolved             ⇒ context.actorSelection(path) ! Identify(1)
    case ActorIdentity(1, Some(ref)) ⇒ { onResolveSuccess(ref); shutdown(DeliveredResolved) }
    case ActorIdentity(1, None)      ⇒ { onResolveFailure(); shutdown(DeliveredUnresolved) }
    case ReceiveTimeout              ⇒ { onResolveFailure(); shutdown(DeliveredUnresolved) }
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
private class ResolvedDestinationDelivery(persistent: PersistentImpl, destination: ActorRef, sdr: ActorRef) extends ResolvedDelivery {
  val path = destination.path
  def onResolveSuccess(ref: ActorRef) = ref tell (persistent.copy(resolved = true), sdr)
  def onResolveFailure() = destination tell (persistent, sdr)
}

/**
 * Resolves `sdr` before sending `persistent` message to specified `destination` using
 * the resolved sender as message sender.
 */
private class ResolvedSenderDelivery(persistent: PersistentImpl, destination: ActorRef, sdr: ActorRef) extends ResolvedDelivery {
  val path = sdr.path
  def onResolveSuccess(ref: ActorRef) = destination tell (persistent.copy(resolved = true), ref)
  def onResolveFailure() = destination tell (persistent, sdr)
}

