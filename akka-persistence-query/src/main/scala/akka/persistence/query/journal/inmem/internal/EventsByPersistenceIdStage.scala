/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.inmem.internal

import akka.actor.ActorRef
import akka.annotation.InternalApi
import akka.persistence.JournalProtocol.RecoverySuccess
import akka.persistence.JournalProtocol.ReplayMessagesFailure
import akka.persistence.Persistence
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.journal.inmem.InmemJournal.{ MessageWithMeta, ReplayWithMeta }
import akka.persistence.query.EventEnvelope
import akka.persistence.query.Sequence
import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, TimerGraphStageLogicWithLogging }

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object EventsByPersistenceIdStage {
  case object Continue
}

/**
 * INTERNAL API
 */
@InternalApi
final private[akka] class EventsByPersistenceIdStage(
    persistenceId: String,
    fromSequenceNr: Long,
    toSequenceNr: Long,
    maxBufSize: Int,
    writeJournalPluginId: String)
    extends GraphStage[SourceShape[EventEnvelope]] {
  val out: Outlet[EventEnvelope] = Outlet("EventsByPersistenceIdSource")
  override def shape: SourceShape[EventEnvelope] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    val logic = new TimerGraphStageLogicWithLogging(shape) with OutHandler {
      var journal: ActorRef = null
      var stageActorRef: ActorRef = null
      var replayInProgress = false
      var nextSequenceNr = fromSequenceNr
      var highestSequenceNrSeen = fromSequenceNr
      var buffer = Vector.empty[EventEnvelope]

      override protected def logSource: Class[_] = classOf[EventsByPersistenceIdStage]

      override def preStart(): Unit = {
        journal = Persistence(materializer.system).journalFor(writeJournalPluginId)
        stageActorRef = getStageActor(journalInteraction).ref
        materializer.system.eventStream.subscribe(stageActorRef, classOf[InmemJournal.Write])
        requestMore()
      }

      private def requestMore(): Unit = {
        log.debug("requestMore nextSequenceNr {}  highestSequenceNrSeen {}", nextSequenceNr, highestSequenceNrSeen)
        if (!replayInProgress) {
          val limit = maxBufSize - buffer.size
          // make sure there's been events to get
          if (limit > 0 && nextSequenceNr <= highestSequenceNrSeen) {
            replayInProgress = true
            journal ! ReplayWithMeta(nextSequenceNr, toSequenceNr, limit, persistenceId, stageActorRef)
          }
        }
      }

      private def journalInteraction(in: (ActorRef, Any)): Unit = {
        val (_, msg) = in
        msg match {
          case InmemJournal.Write(_, pid, seqNr) =>
            log.info("Message from pid {} seqNr {}", pid, seqNr)
            if (pid == persistenceId) {
              highestSequenceNrSeen = math.max(highestSequenceNrSeen, seqNr)
              requestMore()
            }
          case MessageWithMeta(pr, meta) =>
            val event =
              EventEnvelope(
                offset = Sequence(pr.sequenceNr),
                persistenceId = pr.persistenceId,
                sequenceNr = pr.sequenceNr,
                event = pr.payload,
                timestamp = pr.timestamp,
                Option(meta.orNull))
            nextSequenceNr = pr.sequenceNr + 1
            buffer :+= event

          case RecoverySuccess(_) =>
            replayInProgress = false
            deliver()

            log.debug(
              "Replay complete. From sequenceNr {} currentSequenceNr {} toSequenceNr {} buffer size {}",
              fromSequenceNr,
              nextSequenceNr,
              toSequenceNr,
              buffer.size)

            if (nextSequenceNr < toSequenceNr) {
              // this checks if there is room in the buffer
              requestMore()
            }

          case ReplayMessagesFailure(cause) =>
            failStage(cause)
        }
      }

      def deliver(): Unit = {
        buffer match {
          case head +: tail if isAvailable(out) =>
            buffer = tail
            push(out, head)
            if (head.sequenceNr == toSequenceNr) {
              completeStage()
            }
          case _ =>
        }
      }

      override def onPull(): Unit = {
        requestMore()
        deliver()
      }

      setHandler(out, this)
    }
    logic
  }
}
