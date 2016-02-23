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

/**
 * INTERNAL API
 */
private[akka] object EventsByPersistenceIdPublisher {
  def props(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, refreshInterval: Option[FiniteDuration],
            maxBufSize: Int, writeJournalPluginId: String): Props = {
    refreshInterval match {
      case Some(interval) ⇒
        Props(new LiveEventsByPersistenceIdPublisher(persistenceId, fromSequenceNr, toSequenceNr, interval,
          maxBufSize, writeJournalPluginId))
      case None ⇒
        Props(new CurrentEventsByPersistenceIdPublisher(persistenceId, fromSequenceNr, toSequenceNr,
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
private[akka] abstract class AbstractEventsByPersistenceIdPublisher(
  val persistenceId: String, val fromSequenceNr: Long,
  val maxBufSize: Int, val writeJournalPluginId: String)
  extends ActorPublisher[EventEnvelope] with DeliveryBuffer[EventEnvelope] with ActorLogging {
  import EventsByPersistenceIdPublisher._

  val journal: ActorRef = Persistence(context.system).journalFor(writeJournalPluginId)

  var currSeqNo = fromSequenceNr

  def toSequenceNr: Long

  def receive = init

  def init: Receive = {
    case _: Request ⇒ receiveInitialRequest()
    case Continue   ⇒ // skip, wait for first Request
    case Cancel     ⇒ context.stop(self)
  }

  def receiveInitialRequest(): Unit

  def idle: Receive = {
    case Continue | _: LeveldbJournal.EventAppended ⇒
      if (timeForReplay)
        replay()

    case _: Request ⇒
      receiveIdleRequest()

    case Cancel ⇒
      context.stop(self)
  }

  def receiveIdleRequest(): Unit

  def timeForReplay: Boolean =
    (buf.isEmpty || buf.size <= maxBufSize / 2) && (currSeqNo <= toSequenceNr)

  def replay(): Unit = {
    val limit = maxBufSize - buf.size
    log.debug("request replay for persistenceId [{}] from [{}] to [{}] limit [{}]", persistenceId, currSeqNo, toSequenceNr, limit)
    journal ! ReplayMessages(currSeqNo, toSequenceNr, limit, persistenceId, self)
    context.become(replaying(limit))
  }

  def replaying(limit: Int): Receive = {
    case ReplayedMessage(p) ⇒
      buf :+= EventEnvelope(
        offset = p.sequenceNr,
        persistenceId = persistenceId,
        sequenceNr = p.sequenceNr,
        event = p.payload)
      currSeqNo = p.sequenceNr + 1
      deliverBuf()

    case RecoverySuccess(highestSeqNr) ⇒
      log.debug("replay completed for persistenceId [{}], currSeqNo [{}]", persistenceId, currSeqNo)
      receiveRecoverySuccess(highestSeqNr)

    case ReplayMessagesFailure(cause) ⇒
      log.debug("replay failed for persistenceId [{}], due to [{}]", persistenceId, cause.getMessage)
      deliverBuf()
      onErrorThenStop(cause)

    case _: Request ⇒
      deliverBuf()

    case Continue | _: LeveldbJournal.EventAppended ⇒ // skip during replay

    case Cancel ⇒
      context.stop(self)
  }

  def receiveRecoverySuccess(highestSeqNr: Long): Unit
}

/**
 * INTERNAL API
 */
private[akka] class LiveEventsByPersistenceIdPublisher(
  persistenceId: String, fromSequenceNr: Long, override val toSequenceNr: Long,
  refreshInterval: FiniteDuration,
  maxBufSize: Int, writeJournalPluginId: String)
  extends AbstractEventsByPersistenceIdPublisher(
    persistenceId, fromSequenceNr, maxBufSize, writeJournalPluginId) {
  import EventsByPersistenceIdPublisher._

  val tickTask =
    context.system.scheduler.schedule(refreshInterval, refreshInterval, self, Continue)(context.dispatcher)

  override def postStop(): Unit =
    tickTask.cancel()

  override def receiveInitialRequest(): Unit = {
    journal ! LeveldbJournal.SubscribePersistenceId(persistenceId)
    replay()
  }

  override def receiveIdleRequest(): Unit = {
    deliverBuf()
    if (buf.isEmpty && currSeqNo > toSequenceNr)
      onCompleteThenStop()
  }

  override def receiveRecoverySuccess(highestSeqNr: Long): Unit = {
    deliverBuf()
    if (buf.isEmpty && currSeqNo > toSequenceNr)
      onCompleteThenStop()
    context.become(idle)
  }

}

/**
 * INTERNAL API
 */
private[akka] class CurrentEventsByPersistenceIdPublisher(
  persistenceId: String, fromSequenceNr: Long, var toSeqNr: Long,
  maxBufSize: Int, writeJournalPluginId: String)
  extends AbstractEventsByPersistenceIdPublisher(
    persistenceId, fromSequenceNr, maxBufSize, writeJournalPluginId) {
  import EventsByPersistenceIdPublisher._

  override def toSequenceNr: Long = toSeqNr

  override def receiveInitialRequest(): Unit =
    replay()

  override def receiveIdleRequest(): Unit = {
    deliverBuf()
    if (buf.isEmpty && currSeqNo > toSequenceNr)
      onCompleteThenStop()
    else
      self ! Continue
  }

  override def receiveRecoverySuccess(highestSeqNr: Long): Unit = {
    deliverBuf()
    if (highestSeqNr < toSequenceNr)
      toSeqNr = highestSeqNr
    if (buf.isEmpty && (currSeqNo > toSequenceNr || currSeqNo == fromSequenceNr))
      onCompleteThenStop()
    else
      self ! Continue // more to fetch
    context.become(idle)
  }

}
