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
import com.github.ghik.silencer.silent
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

object PersistenceTestKitReadJournal {
  val Identifier = "akka.persistence.testkit.query"
}

@silent("value config") // is never used
final class PersistenceTestKitReadJournal(system: ExtendedActorSystem, config: Config, configPath: String)
    extends ReadJournal
    with EventsByPersistenceIdQuery
    with CurrentEventsByPersistenceIdQuery {

  private val log = LoggerFactory.getLogger(getClass)

  private val storage: EventStorage = {
    // use shared path up to before `query` to identify which inmem journal we are addressing
    val storagePluginId = configPath.replaceAll("""query$""", "journal")
    log.debug("Using in memory storage [{}] for test kit read journal", storagePluginId)
    InMemStorageExtension(system).storageFor(storagePluginId)
  }

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
