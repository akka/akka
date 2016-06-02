/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.query.journal.leveldb

import scala.concurrent.duration._
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.persistence.JournalProtocol._
import akka.persistence.Persistence
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.Cancel
import akka.stream.actor.ActorPublisherMessage.Request
import akka.persistence.journal.leveldb.LeveldbJournal
import akka.persistence.query.EventEnvelope
import akka.persistence.journal.leveldb.LeveldbJournal.ReplayTaggedMessages
import akka.persistence.journal.leveldb.LeveldbJournal.ReplayedTaggedMessage

/**
 * INTERNAL API
 */
private[akka] object EventsByTagPublisher {
  def props(tag: String, fromOffset: Long, toOffset: Long, refreshInterval: Option[FiniteDuration],
            maxBufSize: Int, writeJournalPluginId: String): Props = {
    refreshInterval match {
      case Some(interval) ⇒
        Props(new LiveEventsByTagPublisher(tag, fromOffset, toOffset, interval,
          maxBufSize, writeJournalPluginId))
      case None ⇒
        Props(new CurrentEventsByTagPublisher(tag, fromOffset, toOffset,
          maxBufSize, writeJournalPluginId))
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] case object Continue
}

/**
 * INTERNAL API
 */
private[akka] abstract class AbstractEventsByTagPublisher(
  val tag: String, val fromOffset: Long,
  val maxBufSize: Int, val writeJournalPluginId: String)
  extends ActorPublisher[EventEnvelope] with DeliveryBuffer[EventEnvelope] with ActorLogging {
  import EventsByTagPublisher._

  val journal: ActorRef = Persistence(context.system).journalFor(writeJournalPluginId)

  var currOffset = fromOffset

  def toOffset: Long

  def receive = init

  def init: Receive = {
    case _: Request ⇒ receiveInitialRequest()
    case Continue   ⇒ // skip, wait for first Request
    case Cancel     ⇒ context.stop(self)
  }

  def receiveInitialRequest(): Unit

  def idle: Receive = {
    case Continue | _: LeveldbJournal.TaggedEventAppended ⇒
      if (timeForReplay)
        replay()

    case _: Request ⇒
      receiveIdleRequest()

    case Cancel ⇒
      context.stop(self)
  }

  def receiveIdleRequest(): Unit

  def timeForReplay: Boolean =
    (buf.isEmpty || buf.size <= maxBufSize / 2) && (currOffset <= toOffset)

  def replay(): Unit = {
    val limit = maxBufSize - buf.size
    log.debug("request replay for tag [{}] from [{}] to [{}] limit [{}]", tag, currOffset, toOffset, limit)
    journal ! ReplayTaggedMessages(currOffset, toOffset, limit, tag, self)
    context.become(replaying(limit))
  }

  def replaying(limit: Int): Receive = {
    case ReplayedTaggedMessage(p, _, offset) ⇒
      buf :+= EventEnvelope(
        offset = offset,
        persistenceId = p.persistenceId,
        sequenceNr = p.sequenceNr,
        event = p.payload)
      currOffset = offset + 1
      deliverBuf()

    case RecoverySuccess(highestSeqNr) ⇒
      log.debug("replay completed for tag [{}], currOffset [{}]", tag, currOffset)
      receiveRecoverySuccess(highestSeqNr)

    case ReplayMessagesFailure(cause) ⇒
      log.debug("replay failed for tag [{}], due to [{}]", tag, cause.getMessage)
      deliverBuf()
      onErrorThenStop(cause)

    case _: Request ⇒
      deliverBuf()

    case Continue | _: LeveldbJournal.TaggedEventAppended ⇒ // skip during replay

    case Cancel ⇒
      context.stop(self)
  }

  def receiveRecoverySuccess(highestSeqNr: Long): Unit
}

/**
 * INTERNAL API
 */
private[akka] class LiveEventsByTagPublisher(
  tag: String, fromOffset: Long, override val toOffset: Long,
  refreshInterval: FiniteDuration,
  maxBufSize:      Int, writeJournalPluginId: String)
  extends AbstractEventsByTagPublisher(
    tag, fromOffset, maxBufSize, writeJournalPluginId) {
  import EventsByTagPublisher._

  val tickTask =
    context.system.scheduler.schedule(refreshInterval, refreshInterval, self, Continue)(context.dispatcher)

  override def postStop(): Unit =
    tickTask.cancel()

  override def receiveInitialRequest(): Unit = {
    journal ! LeveldbJournal.SubscribeTag(tag)
    replay()
  }

  override def receiveIdleRequest(): Unit = {
    deliverBuf()
    if (buf.isEmpty && currOffset > toOffset)
      onCompleteThenStop()
  }

  override def receiveRecoverySuccess(highestSeqNr: Long): Unit = {
    deliverBuf()
    if (buf.isEmpty && currOffset > toOffset)
      onCompleteThenStop()
    context.become(idle)
  }

}

/**
 * INTERNAL API
 */
private[akka] class CurrentEventsByTagPublisher(
  tag: String, fromOffset: Long, var _toOffset: Long,
  maxBufSize: Int, writeJournalPluginId: String)
  extends AbstractEventsByTagPublisher(
    tag, fromOffset, maxBufSize, writeJournalPluginId) {
  import EventsByTagPublisher._

  override def toOffset: Long = _toOffset

  override def receiveInitialRequest(): Unit =
    replay()

  override def receiveIdleRequest(): Unit = {
    deliverBuf()
    if (buf.isEmpty && currOffset > toOffset)
      onCompleteThenStop()
    else
      self ! Continue
  }

  override def receiveRecoverySuccess(highestSeqNr: Long): Unit = {
    deliverBuf()
    if (highestSeqNr < toOffset)
      _toOffset = highestSeqNr
    if (buf.isEmpty && (currOffset > toOffset || currOffset == fromOffset))
      onCompleteThenStop()
    else
      self ! Continue // more to fetch
    context.become(idle)
  }

}
