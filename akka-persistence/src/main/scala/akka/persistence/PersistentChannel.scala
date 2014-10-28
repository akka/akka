/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.AkkaException
import akka.actor._
import akka.persistence.JournalProtocol._

/**
 * A [[PersistentChannel]] configuration object.
 *
 * @param redeliverMax Maximum number of redelivery attempts.
 * @param redeliverInterval Interval between redelivery attempts.
 * @param redeliverFailureListener Receiver of [[RedeliverFailure]] notifications which are sent when the number
 *                                 of redeliveries reaches `redeliverMax` for a sequence of messages. To enforce
 *                                 a redelivery of these messages, the listener has to [[Reset]] the persistent
 *                                 channel. Alternatively, it can also confirm these messages, preventing further
 *                                 redeliveries.
 * @param replyPersistent If `true` the sender will receive the successfully stored [[Persistent]] message that has
 *                        been submitted with a [[Deliver]] request, or a [[PersistenceFailure]] message in case of
 *                        a persistence failure.
 * @param pendingConfirmationsMax Message delivery is suspended by a channel if the number of pending reaches the
 *                                specified value and is resumed again if the number of pending confirmations falls
 *                                below `pendingConfirmationsMin`.
 * @param pendingConfirmationsMin Message delivery is resumed if the number of pending confirmations falls below
 *                                this limit. It is suspended again if it reaches `pendingConfirmationsMax`.
 *                                Message delivery is enabled for a channel if the number of pending confirmations
 *                                is below this limit, or, is resumed again if it falls below this limit.
 * @param idleTimeout Maximum interval between read attempts made by a persistent channel. This settings applies,
 *                    for example, after a journal failed to serve a read request. The next read request is then
 *                    made after the configured timeout.
 */
@SerialVersionUID(1L)
@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
final case class PersistentChannelSettings(
  val redeliverMax: Int = 5,
  val redeliverInterval: FiniteDuration = 5.seconds,
  val redeliverFailureListener: Option[ActorRef] = None,
  val replyPersistent: Boolean = false,
  val pendingConfirmationsMax: Long = Long.MaxValue,
  val pendingConfirmationsMin: Long = Long.MaxValue,
  val idleTimeout: FiniteDuration = 1.minute) {

  /**
   * Java API.
   */
  def withRedeliverMax(redeliverMax: Int): PersistentChannelSettings =
    copy(redeliverMax = redeliverMax)

  /**
   * Java API.
   */
  def withRedeliverInterval(redeliverInterval: FiniteDuration): PersistentChannelSettings =
    copy(redeliverInterval = redeliverInterval)

  /**
   * Java API.
   */
  def withRedeliverFailureListener(redeliverFailureListener: ActorRef): PersistentChannelSettings =
    copy(redeliverFailureListener = Option(redeliverFailureListener))

  /**
   * Java API.
   */
  def withReplyPersistent(replyPersistent: Boolean): PersistentChannelSettings =
    copy(replyPersistent = replyPersistent)

  /**
   * Java API.
   */
  def withPendingConfirmationsMax(pendingConfirmationsMax: Long): PersistentChannelSettings =
    copy(pendingConfirmationsMax = pendingConfirmationsMax)

  /**
   * Java API.
   */
  def withPendingConfirmationsMin(pendingConfirmationsMin: Long): PersistentChannelSettings =
    copy(pendingConfirmationsMin = pendingConfirmationsMin)

  /**
   * Java API.
   */
  def withIdleTimeout(idleTimeout: FiniteDuration): PersistentChannelSettings =
    copy(idleTimeout = idleTimeout)

  /**
   * Converts this configuration object to [[ChannelSettings]].
   */
  def toChannelSettings: ChannelSettings =
    ChannelSettings(redeliverMax, redeliverInterval, redeliverFailureListener)
}

@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
object PersistentChannelSettings {
  /**
   * Java API.
   */
  def create() = PersistentChannelSettings.apply()
}

/**
 * Resets a [[PersistentChannel]], forcing it to redeliver all unconfirmed persistent
 * messages. This does not affect writing [[Deliver]] requests.
 */
@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
case object Reset {
  /**
   * Java API.
   */
  def getInstance() = this
}

/**
 * Exception thrown by a [[PersistentChannel]] child actor to re-initiate delivery.
 */
@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
class ResetException extends AkkaException("Channel reset on application request")

/**
 * A [[PersistentChannel]] implements the same functionality as a [[Channel]] but additionally persists
 * [[Deliver]] requests before they are served. Persistent channels are useful in combination with slow
 * destinations or destinations that are unavailable for a long time. `Deliver` requests that have been
 * persisted by a persistent channel are deleted when destinations confirm the receipt of the corresponding
 * messages.
 *
 * The number of pending confirmations can be limited by a persistent channel based on the parameters of
 * [[PersistentChannelSettings]]. It can suspend delivery when the number of pending confirmations reaches
 * `pendingConfirmationsMax` and resume delivery again when this number falls below `pendingConfirmationsMin`.
 * This prevents both flooding destinations with more messages than they can process and unlimited memory
 * consumption by the channel. A persistent channel continues to persist [[Deliver]] request even when
 * message delivery is temporarily suspended.
 *
 * A persistent channel can also reply to [[Deliver]] senders if the request has been successfully persisted
 * or not (see `replyPersistent` parameter in [[PersistentChannelSettings]]). In case of success, the channel
 * replies with the contained [[Persistent]] message, otherwise with a [[PersistenceFailure]] message.
 */
@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
final class PersistentChannel private[akka] (_channelId: Option[String], channelSettings: PersistentChannelSettings) extends Actor {
  private val id = _channelId match {
    case Some(cid) ⇒ cid
    case None      ⇒ Persistence(context.system).channelId(self)
  }

  private val requestReader = context.actorOf(Props(classOf[RequestReader], id, channelSettings))
  private val requestWriter = context.actorOf(Props(classOf[RequestWriter], id, channelSettings, requestReader))

  def receive = {
    case d @ Deliver(persistent: PersistentRepr, destination) ⇒
      // Persist the Deliver request by sending reliableStorage a Persistent message
      // with the Deliver request as payload. This persistent message is referred to
      // as the wrapper message, whereas the persistent message contained in the Deliver
      // request is referred to as wrapped message.
      if (!persistent.confirms.contains(id)) requestWriter forward Persistent(d)
    case Reset ⇒ requestReader ! Reset
  }
}

@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
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
 * Plugin API.
 */
@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
final case class DeliveredByPersistentChannel(
  channelId: String,
  persistentSequenceNr: Long,
  deliverySequenceNr: Long = 0L,
  channel: ActorRef = null) extends Delivered with PersistentId {

  override def persistenceId: String = channelId
  @deprecated("Use persistenceId.", since = "2.3.4")
  override def processorId = persistenceId
  def sequenceNr: Long = persistentSequenceNr
  def update(deliverySequenceNr: Long, channel: ActorRef): DeliveredByPersistentChannel =
    copy(deliverySequenceNr = deliverySequenceNr, channel = channel)
}

/**
 * INTERNAL API.
 */
@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
private[persistence] class DeliveredByPersistentChannelBatching(journal: ActorRef, settings: PersistenceSettings) extends Actor {
  private val publish = settings.internal.publishConfirmations
  private val batchMax = settings.journal.maxConfirmationBatchSize

  private var batching = false
  private var batch = Vector.empty[DeliveredByPersistentChannel]

  def receive = {
    case DeleteMessagesSuccess(messageIds) ⇒
      if (batch.isEmpty) batching = false else journalBatch()
      messageIds.foreach {
        case c: DeliveredByPersistentChannel ⇒
          c.channel ! c
          if (publish) context.system.eventStream.publish(c)
      }
    case DeleteMessagesFailure(_) ⇒
      if (batch.isEmpty) batching = false else journalBatch()
    case d: DeliveredByPersistentChannel ⇒
      addToBatch(d)
      if (!batching || maxBatchSizeReached) journalBatch()
    case m ⇒ journal forward m
  }

  def addToBatch(pc: DeliveredByPersistentChannel): Unit =
    batch = batch :+ pc

  def maxBatchSizeReached: Boolean =
    batch.length >= batchMax

  def journalBatch(): Unit = {
    journal ! DeleteMessages(batch, true, Some(self))
    batch = Vector.empty
    batching = true
  }
}

/**
 * Writes [[Deliver]] requests to the journal.
 */
@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
private class RequestWriter(channelId: String, channelSettings: PersistentChannelSettings, reader: ActorRef) extends Processor {
  import RequestWriter._
  import channelSettings._

  private val cbJournal = extension.confirmationBatchingJournalForChannel(channelId)

  override val persistenceId = channelId

  def receive = {
    case p @ Persistent(Deliver(wrapped: PersistentRepr, _), _) ⇒
      if (!recoveryRunning && wrapped.persistenceId != PersistentRepr.Undefined) {
        // Write a delivery confirmation to the journal so that replayed Deliver
        // requests from a sending processor are not persisted again. Replaying
        // Deliver requests is now the responsibility of this processor
        // and confirmation by destination is done to the wrapper p.sequenceNr.
        cbJournal ! DeliveredByChannel(wrapped.persistenceId, channelId, wrapped.sequenceNr)
      }

      if (!recoveryRunning && replyPersistent)
        sender() ! wrapped

    case p: PersistenceFailure ⇒
      if (replyPersistent) sender() ! p
  }

  override protected[akka] def aroundReceive(receive: Receive, message: Any): Unit = {
    super.aroundReceive(receive, message)
    message match {
      case WriteMessagesSuccessful | WriteMessagesFailed(_) ⇒
        // activate reader after to reduce delivery latency
        reader ! RequestsWritten
      case _ ⇒
    }
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    self ! Recover(replayMax = 0L)
  }

  override def preStart(): Unit = {
    self ! Recover(replayMax = 0L)
  }
}

@deprecated("Channel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
private object RequestWriter {
  case object RequestsWritten
}

/**
 * Reads [[Deliver]] requests from the journal and processes them. The number of `Deliver` requests
 * processed per iteration depends on
 *
 *  - `pendingConfirmationsMax` parameter in [[PersistentChannelSettings]]
 *  - `pendingConfirmationsMin` parameter in [[PersistentChannelSettings]] and the
 *  - current number of pending confirmations.
 *
 * @see [[PersistentChannel]]
 */
@deprecated("PersistentChannel will be removed, see `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
private class RequestReader(channelId: String, channelSettings: PersistentChannelSettings) extends Actor with Recovery {
  import RequestWriter._
  import channelSettings._

  private val delivery = context.actorOf(Props(classOf[ReliableDelivery], channelSettings.toChannelSettings))

  private val idle: State = new State {
    override def toString: String = "idle"

    def aroundReceive(receive: Receive, message: Any): Unit = message match {
      case r: Recover ⇒ // ignore
      case other      ⇒ process(receive, other)
    }
  }

  def receive = {
    case p @ Persistent(d @ Deliver(wrapped: PersistentRepr, destination), snr) ⇒
      val wrapper = p.asInstanceOf[PersistentRepr]
      val prepared = prepareDelivery(wrapped, wrapper)
      numReplayed += 1
      numPending += 1
      delivery forward d.copy(prepared)
    case d: Delivered ⇒
      delivery forward d
      numPending = math.max(numPending - 1L, 0L)
      if (numPending == pendingConfirmationsMin) onReadRequest()
    case d @ RedeliverFailure(ms) ⇒
      val numPendingPrev = numPending
      numPending = math.max(numPending - ms.length, 0L)
      if (numPendingPrev > pendingConfirmationsMin && numPending <= pendingConfirmationsMin) onReadRequest()
      redeliverFailureListener.foreach(_.tell(d, context.parent))
    case RequestsWritten | ReceiveTimeout ⇒
      if (numPending <= pendingConfirmationsMin) onReadRequest()
    case Reset ⇒ throw new ResetException
  }

  def onReplaySuccess(receive: Receive, await: Boolean): Unit = {
    onReplayComplete()
    if (numReplayed > 0 && numPending <= pendingConfirmationsMin) onReadRequest()
    numReplayed = 0L
  }

  def onReplayFailure(receive: Receive, await: Boolean, cause: Throwable): Unit = {
    onReplayComplete()
  }

  override def persistenceId: String = channelId

  def snapshotterId: String =
    s"${channelId}-reader"

  private val dbJournal = extension.deletionBatchingJournalForChannel(channelId)

  /**
   * Number of delivery requests replayed (read) per iteration.
   */
  private var numReplayed = 0L

  /**
   * Number of pending confirmations.
   */
  private var numPending = 0L

  context.setReceiveTimeout(channelSettings.idleTimeout)

  private def onReplayComplete(): Unit = {
    _currentState = idle
    receiverStash.unstashAll()
  }

  private def onReadRequest(): Unit = if (_currentState == idle) {
    _currentState = replayStarted(await = false)
    dbJournal ! ReplayMessages(lastSequenceNr + 1L, Long.MaxValue, pendingConfirmationsMax - numPending, persistenceId, self)
  }

  /**
   * @param wrapped persistent message contained in a deliver request
   * @param wrapper persistent message that contains a deliver request
   */
  private def prepareDelivery(wrapped: PersistentRepr, wrapper: PersistentRepr): PersistentRepr = {
    ConfirmablePersistentImpl(wrapped,
      confirmTarget = dbJournal,
      confirmMessage = DeliveredByPersistentChannel(channelId, wrapper.sequenceNr, channel = self))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    try receiverStash.unstashAll() finally super.preRestart(reason, message)
  }

  override def preStart(): Unit = {
    super.preStart()
    self ! Recover(replayMax = 0L)
    self ! RequestsWritten // considers savepoint loaded from snapshot (TODO)
  }
}
