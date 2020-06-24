/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query.scaladsl
import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.persistence.query.{ EventEnvelope, Sequence }
import akka.persistence.query.scaladsl.{ CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal }
import akka.persistence.testkit.EventStorage
import akka.persistence.testkit.internal.InMemStorageExtension
import akka.persistence.testkit.query.internal.EventsByPersistenceIdStage
import akka.stream.scaladsl.Source

object PersistenceTestKitReadJournal {
  val Identifier = "akka.persistence.testkit.query"
}

final class PersistenceTestKitReadJournal(system: ExtendedActorSystem)
    extends ReadJournal
    with EventsByPersistenceIdQuery
    with CurrentEventsByPersistenceIdQuery {

  private val storage: EventStorage = InMemStorageExtension(system)

  override def eventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    Source.fromGraph(new EventsByPersistenceIdStage(persistenceId, fromSequenceNr, toSequenceNr, storage))
  }

  override def currentEventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    Source(storage.tryRead(persistenceId, fromSequenceNr, toSequenceNr, Long.MaxValue)).map { pr =>
      EventEnvelope(Sequence(pr.sequenceNr), persistenceId, pr.sequenceNr, pr.payload, pr.timestamp, pr.metadata)
    }
  }
}
