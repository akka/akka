/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
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
            maxBufSize: Int, writeJournalPluginId: String): Props =
    Props(new EventsByPersistenceIdPublisher(persistenceId, fromSequenceNr, toSequenceNr, refreshInterval,
      maxBufSize, writeJournalPluginId))

  private case object Continue
}

class EventsByPersistenceIdPublisher(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long,
                                     refreshInterval: Option[FiniteDuration],
                                     maxBufSize: Int, writeJournalPluginId: String)
  extends ActorPublisher[EventEnvelope] with DeliveryBuffer[EventEnvelope] with ActorLogging {
  import EventsByPersistenceIdPublisher._

  val journal: ActorRef = Persistence(context.system).journalFor(writeJournalPluginId)

  var currSeqNo = fromSequenceNr

  val tickTask = refreshInterval.map { interval ⇒
    import context.dispatcher
    context.system.scheduler.schedule(interval, interval, self, Continue)
  }

  def nonLiveQuery: Boolean = refreshInterval.isEmpty

  override def postStop(): Unit = {
    tickTask.foreach(_.cancel())
  }

  def receive = init

  def init: Receive = {
    case _: Request ⇒
      journal ! LeveldbJournal.SubscribePersistenceId(persistenceId)
      replay()
    case Continue ⇒ // skip, wait for first Request
    case Cancel   ⇒ context.stop(self)
  }

  def idle: Receive = {
    case Continue | _: LeveldbJournal.ChangedPersistenceId ⇒
      if (timeForReplay)
        replay()

    case _: Request ⇒
      deliverBuf()
      if (nonLiveQuery) {
        if (buf.isEmpty)
          onCompleteThenStop()
        else
          self ! Continue
      }

    case Cancel ⇒
      context.stop(self)

  }

  def timeForReplay: Boolean =
    buf.isEmpty || buf.size <= maxBufSize / 2

  def replay(): Unit = {
    val limit = maxBufSize - buf.size
    log.debug("request replay for persistenceId [{}] from [{}] to [{}] limit [{}]", persistenceId, currSeqNo, toSequenceNr, limit)
    journal ! ReplayMessages(currSeqNo, toSequenceNr, limit, persistenceId, self)
    context.become(replaying(limit))
  }

  def replaying(limit: Int): Receive = {
    var replayCount = 0

    {
      case ReplayedMessage(p) ⇒
        buf :+= EventEnvelope(
          offset = p.sequenceNr,
          persistenceId = persistenceId,
          sequenceNr = p.sequenceNr,
          event = p.payload)
        currSeqNo = p.sequenceNr + 1
        replayCount += 1
        deliverBuf()

      case _: RecoverySuccess ⇒
        log.debug("replay completed for persistenceId [{}], currSeqNo [{}], replayCount [{}]", persistenceId, currSeqNo, replayCount)
        deliverBuf()
        if (buf.isEmpty && currSeqNo > toSequenceNr)
          onCompleteThenStop()
        else if (nonLiveQuery) {
          if (buf.isEmpty && replayCount < limit)
            onCompleteThenStop()
          else
            self ! Continue // more to fetch
        }
        context.become(idle)

      case ReplayMessagesFailure(cause) ⇒
        log.debug("replay failed for persistenceId [{}], due to [{}]", persistenceId, cause.getMessage)
        deliverBuf()
        onErrorThenStop(cause)

      case _: Request ⇒
        deliverBuf()

      case Continue | _: LeveldbJournal.ChangedPersistenceId ⇒ // skip during replay

      case Cancel ⇒
        context.stop(self)
    }
  }

}
