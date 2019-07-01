/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.actor.ActorRef
import akka.persistence.JournalProtocol.{RecoverySuccess, ReplayMessagesFailure}
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.LeveldbJournal.{ReplayTaggedMessages, ReplayedTaggedMessage}
import akka.persistence.query.{EventEnvelope, Sequence}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, TimerGraphStageLogicWithLogging}
import akka.stream.{ActorMaterializer, Attributes, Outlet, SourceShape}
import com.github.ghik.silencer.silent

import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@silent // FIXME
private[leveldb] class EventsByTagGraphStage(
    val tag: String,
    val fromOffset: Long,
    maxBufSize: Int,
    initialTooOffset: Long,
    writeJournalPluginId: String,
    mat: ActorMaterializer,
    refreshInterval: Option[FiniteDuration])
    extends GraphStage[SourceShape[EventEnvelope]] {

  val out: Outlet[EventEnvelope] = Outlet("EventsByTagSource")

  override def shape: SourceShape[EventEnvelope] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    val logic = new TimerGraphStageLogicWithLogging(shape) with OutHandler {
      val journal: ActorRef = Persistence(mat.system).journalFor(writeJournalPluginId)
      var buf: Vector[EventEnvelope] = Vector.empty[EventEnvelope]
      var currOffset: Long = fromOffset
      var toOffset: Long = initialTooOffset
      var stageActorRef: ActorRef = null

      override def preStart(): Unit =  {
        stageActorRef = getStageActor(journalInteraction).ref
        requestMore()
      }

      private def requestMore(): Unit = {
        //FIXME
        val limit = maxBufSize - buf.size
        val request = ReplayTaggedMessages(currOffset, toOffset, limit, tag, stageActorRef)
        log.info("Requesting: {}", request)
        journal ! request
      }

      // TODO: Handle TaggedEventAppeneded for live query
      def journalInteraction(in: (ActorRef, Any)): Unit = {
        val (_, msg) = in
        msg match {
          case ReplayedTaggedMessage(p, _, offset) =>
            buf :+= EventEnvelope(
            offset = Sequence(offset),
            persistenceId = p.persistenceId,
            sequenceNr = p.sequenceNr,
            event = p.payload)
            currOffset = offset
            deliverBuf()
            // FIXME
            log.info("Buffer: {}", buf)

          case RecoverySuccess(highestSeqNr) =>
            deliverBuf()
            log.info("Current offset {} toOffset {} buffer size {} highestSeqNr {}", currOffset, toOffset, buf.size, highestSeqNr)
            if (highestSeqNr < toOffset) {
              // TODO for live queries the highestSeqNr doesn't matter
              toOffset = highestSeqNr
            }
            if (currOffset < toOffset) {
              // need further requests to the journal
              if (buf.size == maxBufSize) {
                // no room in buffer, wait for it to be drained to request more
              } else {
                requestMore()
              }
            } else {
              if (buf.isEmpty) {
                // buffer is empty and nothing left from the journal
                completeStage()
              } else {
                // waiting on demand to drain the buffer
              }
            }

          case ReplayMessagesFailure(cause) =>
        }
      }


      def deliverBuf(): Unit = {
        if (buf.nonEmpty && isAvailable(out)) {
          val next = buf.head
          push(out, next)
          buf = buf.tail
        }
      }

      override def onPull(): Unit = {
        // TODO
      }

      setHandler(out, this)
    }

    logic
  }

}
