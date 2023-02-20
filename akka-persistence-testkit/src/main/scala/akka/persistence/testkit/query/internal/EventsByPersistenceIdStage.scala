/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query.internal
import akka.actor.ActorRef
import akka.annotation.InternalApi
import akka.persistence.journal.Tagged
import akka.persistence.query.{ EventEnvelope, Sequence }
import akka.persistence.testkit.{ EventStorage, PersistenceTestKitPlugin }
import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, GraphStageLogicWithLogging, OutHandler }

/**
 * INTERNAL API
 */
@InternalApi
final private[akka] class EventsByPersistenceIdStage(
    persistenceId: String,
    fromSequenceNr: Long,
    toSequenceNr: Long,
    storage: EventStorage)
    extends GraphStage[SourceShape[EventEnvelope]] {
  val out: Outlet[EventEnvelope] = Outlet("EventsByPersistenceIdSource")
  override def shape: SourceShape[EventEnvelope] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogicWithLogging(shape) with OutHandler {
      private var currentSequenceNr = math.max(fromSequenceNr, 1)
      private var stageActorRef: ActorRef = null
      override def preStart(): Unit = {
        stageActorRef = getStageActor(receiveNotifications).ref
        materializer.system.eventStream.subscribe(stageActorRef, classOf[PersistenceTestKitPlugin.Write])
      }

      private def receiveNotifications(in: (ActorRef, Any)): Unit = {
        val (_, msg) = in
        msg match {
          case PersistenceTestKitPlugin.Write(pid, toSequenceNr) if pid == persistenceId =>
            if (toSequenceNr >= currentSequenceNr) {
              tryPush()
            }
          case _ =>
        }
      }

      private def tryPush(): Unit = {
        if (isAvailable(out)) {
          val event = storage.tryRead(persistenceId, currentSequenceNr, currentSequenceNr, 1)
          log.debug("tryPush available. Query for {} {} result {}", currentSequenceNr, currentSequenceNr, event)
          event.headOption match {
            case Some(pr) =>
              push(out, EventEnvelope(Sequence(pr.sequenceNr), pr.persistenceId, pr.sequenceNr, pr.payload match {
                case Tagged(payload, _) => payload
                case payload            => payload
              }, pr.timestamp, pr.metadata))
              if (currentSequenceNr == toSequenceNr) {
                completeStage()
              } else {
                currentSequenceNr += 1
              }
            case None =>
          }
        } else {
          log.debug("tryPush, no demand")
        }
      }

      override def onPull(): Unit = {
        tryPush()
      }

      setHandler(out, this)
    }

  }

}
