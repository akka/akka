/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.NotUsed
import akka.actor.ActorRef
import akka.annotation.InternalApi
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.LeveldbJournal
import akka.stream.Attributes
import akka.stream.Materializer
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler
import akka.stream.stage.TimerGraphStageLogicWithLogging

/**
 * INTERNAL API
 */
@InternalApi
final private[akka] class AllPersistenceIdsStage(liveQuery: Boolean, writeJournalPluginId: String)
    extends GraphStage[SourceShape[String]] {

  val out: Outlet[String] = Outlet("AllPersistenceIds.out")

  override def shape: SourceShape[String] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    throw new UnsupportedOperationException("Not used")

  override private[akka] def createLogicAndMaterializedValue(
      inheritedAttributes: Attributes,
      eagerMaterializer: Materializer): (GraphStageLogic, NotUsed) = {
    val logic = new TimerGraphStageLogicWithLogging(shape) with OutHandler with Buffer[String] {
      setHandler(out, this)
      val journal: ActorRef = Persistence(eagerMaterializer.system).journalFor(writeJournalPluginId)
      var initialResponseReceived = false

      override protected def logSource: Class[_] = classOf[AllPersistenceIdsStage]

      override def preStart(): Unit = {
        journal.tell(LeveldbJournal.SubscribeAllPersistenceIds, getStageActor(journalInteraction).ref)
      }

      private def journalInteraction(in: (ActorRef, Any)): Unit = {
        val (_, msg) = in
        msg match {
          case LeveldbJournal.CurrentPersistenceIds(allPersistenceIds) =>
            buffer(allPersistenceIds)
            deliverBuf(out)
            initialResponseReceived = true
            if (!liveQuery && bufferEmpty)
              completeStage()

          case LeveldbJournal.PersistenceIdAdded(persistenceId) =>
            if (liveQuery) {
              buffer(persistenceId)
              deliverBuf(out)
            }
        }
      }

      override def onPull(): Unit = {
        deliverBuf(out)
        if (initialResponseReceived && !liveQuery && bufferEmpty)
          completeStage()
      }

    }

    (logic, NotUsed)
  }
}
