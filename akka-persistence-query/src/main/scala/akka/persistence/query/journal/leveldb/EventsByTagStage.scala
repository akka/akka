/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.actor.ActorRef
import akka.annotation.InternalApi
import akka.persistence.JournalProtocol.{ RecoverySuccess, ReplayMessagesFailure }
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.LeveldbJournal
import akka.persistence.journal.leveldb.LeveldbJournal.{
  ReplayTaggedMessages,
  ReplayedTaggedMessage,
  TaggedEventAppended
}
import akka.persistence.query.journal.leveldb.EventsByTagStage.Continue
import akka.persistence.query.{ EventEnvelope, Sequence }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, TimerGraphStageLogicWithLogging }
import akka.stream.{ ActorMaterializer, Attributes, Outlet, SourceShape }

import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object EventsByTagStage {
  case object Continue
}

/**
 * INTERNAL API
 */
final private[leveldb] class EventsByTagStage(
    tag: String,
    fromOffset: Long,
    maxBufSize: Int,
    initialTooOffset: Long,
    writeJournalPluginId: String,
    mat: ActorMaterializer,
    refreshInterval: Option[FiniteDuration])
    extends GraphStage[SourceShape[EventEnvelope]] {

  val out: Outlet[EventEnvelope] = Outlet("EventsByTagSource")

  override def shape: SourceShape[EventEnvelope] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    val logic = new TimerGraphStageLogicWithLogging(shape) with OutHandler with Buffer[EventEnvelope] {
      val journal: ActorRef = Persistence(mat.system).journalFor(writeJournalPluginId)
      var currOffset: Long = fromOffset
      var toOffset: Long = initialTooOffset
      var stageActorRef: ActorRef = null
      var replayInProgress = false
      var outstandingReplay = false

      override def preStart(): Unit = {
        stageActorRef = getStageActor(journalInteraction).ref
        refreshInterval.foreach(fd => {
          scheduleWithFixedDelay(Continue, fd, fd)
          journal.tell(LeveldbJournal.SubscribeTag(tag), stageActorRef)
        })
        requestMore()
      }

      override protected def onTimer(timerKey: Any): Unit = {
        requestMore()
        deliverBuf(out)
      }

      private def requestMore(): Unit = {
        if (!replayInProgress) {
          val limit = maxBufSize - bufferSize
          if (limit > 0) {
            replayInProgress = true
            outstandingReplay = false
            val request = ReplayTaggedMessages(currOffset, toOffset, limit, tag, stageActorRef)
            journal ! request
          }
        } else {
          outstandingReplay = true
        }
      }

      private def journalInteraction(in: (ActorRef, Any)): Unit = {
        val (_, msg) = in
        msg match {
          case ReplayedTaggedMessage(p, _, offset) =>
            buffer(
              EventEnvelope(
                offset = Sequence(offset),
                persistenceId = p.persistenceId,
                sequenceNr = p.sequenceNr,
                event = p.payload))
            currOffset = offset
            deliverBuf(out)

          case RecoverySuccess(highestSeqNr) =>
            replayInProgress = false
            deliverBuf(out)
            log.debug(
              "Replay complete. Current offset {} toOffset {} buffer size {} highestSeqNr {}",
              currOffset,
              toOffset,
              bufferSize,
              highestSeqNr)
            // Set toOffset to know when to end the query for current queries
            // live queries go on forever
            if (highestSeqNr < toOffset && isCurrentQuery()) {
              toOffset = highestSeqNr
            }
            if (currOffset < toOffset) {
              // need further requests to the journal
              if (bufferSize < maxBufSize && (isCurrentQuery() || outstandingReplay)) {
                requestMore()
              }
            } else {
              checkComplete()
            }

          case ReplayMessagesFailure(cause) =>
            failStage(cause)

          case TaggedEventAppended(_) =>
            requestMore()
        }
      }

      private def isCurrentQuery(): Boolean = refreshInterval.isEmpty

      private def checkComplete(): Unit = {
        if (bufferEmpty && currOffset >= toOffset) {
          completeStage()
        }
      }

      override def onPull(): Unit = {
        requestMore()
        deliverBuf(out)
        checkComplete()
      }

      setHandler(out, this)
    }

    logic
  }

}
