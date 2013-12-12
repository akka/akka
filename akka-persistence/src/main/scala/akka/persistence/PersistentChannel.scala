/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.AkkaException
import akka.actor._

import akka.persistence.JournalProtocol.Confirm

/**
 * A [[PersistentChannel]] configuration object.
 *
 * @param redeliverMax maximum number of redeliveries (default is 5).
 * @param redeliverInterval interval between redeliveries (default is 5 seconds).
 * @param replyPersistent if `true` the sender will receive the successfully stored [[Persistent]]
 *                        message that has been submitted with a [[Deliver]] request, or a
 *                        [[PersistenceFailure]] message in case of a persistence failure.
 */
class PersistentChannelSettings(
  redeliverMax: Int,
  redeliverInterval: FiniteDuration,
  val replyPersistent: Boolean) extends ChannelSettings(redeliverMax, redeliverInterval) {

  /**
   * Java API.
   */
  override def withRedeliverMax(redeliverMax: Int): PersistentChannelSettings =
    updatePersistent(redeliverMax = redeliverMax)

  /**
   * Java API.
   */
  override def withRedeliverInterval(redeliverInterval: FiniteDuration): PersistentChannelSettings =
    updatePersistent(redeliverInterval = redeliverInterval)

  /**
   * Java API.
   */
  def withReplyPersistent(replayPersistent: Boolean) =
    updatePersistent(replyPersistent = replyPersistent)

  private def updatePersistent( // compile error if method name is 'update'
    redeliverMax: Int = redeliverMax,
    redeliverInterval: FiniteDuration = redeliverInterval,
    replyPersistent: Boolean = replyPersistent): PersistentChannelSettings =
    new PersistentChannelSettings(redeliverMax, redeliverInterval, replyPersistent)
}

object PersistentChannelSettings {
  def apply(
    redeliverMax: Int = 5,
    redeliverInterval: FiniteDuration = 5 seconds,
    replyPersistent: Boolean = false): PersistentChannelSettings =
    new PersistentChannelSettings(redeliverMax, redeliverInterval, replyPersistent)

  /**
   * Java API.
   */
  def create() = apply()
}

/**
 * A [[PersistentChannel]] implements the same functionality as a [[Channel]] but additionally
 * persists messages before they are delivered. This is done by using internally a special-purpose
 * [[Processor]]. Therefore, the main use case of a persistent channel is standalone usage i.e.
 * independent of an application-specific [[Processor]] sending messages to a channel. Messages
 * that have been persisted by a persistent channel are deleted when destinations confirm the
 * receipt of these messages.
 *
 * Using a persistent channel in combination with a [[Processor]] can make sense if destinations
 * are unavailable for a long time and an application doesn't want to buffer all messages in
 * memory (but write them to the journal instead). In this case, delivery can be disabled with
 * [[DisableDelivery]] (to stop delivery and persist-only) and re-enabled with [[EnableDelivery]].
 * `EnableDelivery` replays persistent messages to this channel and the channel delivers all
 * unconfirmed messages again (which may then show up as duplicates at destinations as described
 * in the API docs of [[Channel]]. Duplicates can be detected by tracking message sequence numbers
 * and redelivery counters).
 *
 * A persistent channel can also reply to [[Deliver]] senders whether persisting a message was
 * successful or not (see `replyPersistent` of [[PersistentChannelSettings]]). If enabled, the
 * sender will receive the persisted message as reply (i.e. a [[Persistent]] message), otherwise
 * a [[PersistenceFailure]] message.
 */
final class PersistentChannel private[akka] (_channelId: Option[String], channelSettings: PersistentChannelSettings) extends Actor {
  private val id = _channelId match {
    case Some(cid) ⇒ cid
    case None      ⇒ Persistence(context.system).channelId(self)
  }

  private val reliableDelivery = context.actorOf(Props(classOf[ReliableDelivery], channelSettings))
  private val resolvedDelivery = context.actorOf(Props(classOf[ResolvedDelivery], reliableDelivery))
  private val reliableStorage = context.actorOf(Props(classOf[ReliableStorage], id, channelSettings, resolvedDelivery))

  def receive = {
    case d @ Deliver(persistent: PersistentRepr, destination, resolve) ⇒
      // Persist the Deliver request by sending reliableStorage a Persistent message
      // with the Deliver request as payload. This persistent message is referred to
      // as the wrapper message, whereas the persistent message contained in the Deliver
      // request is referred to as wrapped message (see also class ReliableStorage).
      if (!persistent.confirms.contains(id)) reliableStorage forward Persistent(d)
    case DisableDelivery ⇒ reliableStorage ! DisableDelivery
    case EnableDelivery  ⇒ reliableStorage ! EnableDelivery
  }
}

object PersistentChannel {
  /**
   * Returns a channel actor configuration object for creating a [[PersistentChannel]] with a
   * generated id and default [[PersistentChannelSettings]].
   */
  def props(): Props = props(PersistentChannelSettings())

  /**
   * Returns a channel actor configuration object for creating a [[PersistentChannel]] with a
   * generated id and specified `channelSettings`.
   *
   * @param channelSettings channel configuration object.
   */
  def props(channelSettings: PersistentChannelSettings): Props =
    Props(classOf[PersistentChannel], None, channelSettings)

  /**
   * Returns a channel actor configuration object for creating a [[PersistentChannel]] with the
   * specified id and default [[PersistentChannelSettings]].
   *
   * @param channelId channel id.
   */
  def props(channelId: String): Props =
    props(channelId, PersistentChannelSettings())

  /**
   * Returns a channel actor configuration object for creating a [[PersistentChannel]] with the
   * specified id and specified `channelSettings`.
   *
   * @param channelId channel id.
   * @param channelSettings channel configuration object.
   */
  def props(channelId: String, channelSettings: PersistentChannelSettings): Props =
    Props(classOf[PersistentChannel], Some(channelId), channelSettings)
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

private class ReliableStorage(channelId: String, channelSettings: PersistentChannelSettings, next: ActorRef) extends Processor {
  import channelSettings._

  override val processorId = channelId

  private val journal = Persistence(context.system).journalFor(channelId)
  private var deliveryEnabled = true

  def receive = {
    case p @ Persistent(d @ Deliver(wrapped: PersistentRepr, destination, resolve), snr) ⇒
      val wrapper = p.asInstanceOf[PersistentRepr]
      val prepared = prepareDelivery(wrapped, wrapper)

      if (!recoveryRunning && wrapped.processorId != PersistentRepr.Undefined)
        // Write a delivery confirmation to the journal so that replayed Deliver
        // requests from a sending processor are not persisted again. Replaying
        // Deliver requests is now the responsibility of this processor.
        journal ! Confirm(prepared.processorId, prepared.sequenceNr, channelId)

      if (!recoveryRunning && replyPersistent)
        sender ! prepared

      if (deliveryEnabled)
        next forward d.copy(prepared)

    case p: PersistenceFailure if (replyPersistent) ⇒ sender ! p
    case EnableDelivery if (!deliveryEnabled)       ⇒ throw new ChannelRestartRequiredException
    case DisableDelivery                            ⇒ deliveryEnabled = false
  }

  /**
   * @param wrapped persistent message contained in a deliver request
   * @param wrapper persistent message that contains a deliver request
   */
  private def prepareDelivery(wrapped: PersistentRepr, wrapper: PersistentRepr): PersistentRepr = {
    // use the sequence number of the wrapper message if the channel is used standalone,
    // otherwise, use sequence number of the wrapped message (that has been generated by
    // the sending processor).
    val sequenceNr = if (wrapped.sequenceNr == 0L) wrapper.sequenceNr else wrapped.sequenceNr
    val resolved = wrapped.resolved && wrapper.asInstanceOf[PersistentRepr].resolved
    val updated = wrapped.update(sequenceNr = sequenceNr, resolved = resolved)
    // include the wrapper sequence number in the Confirm message so that the wrapper can
    // be deleted later when the confirmation arrives.
    ConfirmablePersistentImpl(updated,
      confirmTarget = journal,
      confirmMessage = Confirm(updated.processorId, sequenceNr, channelId, wrapper.sequenceNr))
  }
}
