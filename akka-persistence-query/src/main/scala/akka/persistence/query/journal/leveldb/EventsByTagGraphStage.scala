/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.actor.ActorRef
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.LeveldbJournal.ReplayTaggedMessages
import akka.persistence.query.EventEnvelope
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, TimerGraphStageLogicWithLogging }
import akka.stream.{ ActorMaterializerHelper, Attributes, Outlet, SourceShape }

/**
 * INTERNAL API
 */
private[leveldb] class EventsByTagGraphStage(
    val tag: String,
    val fromOffset: Long,
    maxBuffSize: Int,
    writeJournalPluginId: String)
    extends GraphStage[SourceShape[EventEnvelope]] {

  val out: Outlet[EventEnvelope] = Outlet("EventsByTagSource")

  override def shape: SourceShape[EventEnvelope] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    val logic = new TimerGraphStageLogicWithLogging(shape) with OutHandler {
      val system = ActorMaterializerHelper.downcast(materializer).system
      val journal: ActorRef = Persistence(system).journalFor(writeJournalPluginId)
      var buf: Vector[EventEnvelope] = Vector.empty[EventEnvelope]
      var currOffset: Long = fromOffset
      var toOffset: Long = ???

      var replaying = false

      def deliverBuf(): Unit = {
        if (buf.nonEmpty && isAvailable(out)) {
          // optimize for this common case
          push(out, buf.head)
          buf = buf.tail
        }
      }

      def replay(): Unit = {
        val limit = maxBufSize - buf.size
        log.debug("request replay for tag [{}] from [{}] to [{}] limit [{}]", tag, currOffset, toOffset, limit)
        journal ! ReplayTaggedMessages(currOffset, toOffset, limit, tag, self)
        replaying = true
      }

      override def onPull(): Unit = {}

      setHandler(out, this)
    }

    logic
  }

}
