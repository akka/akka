/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query.scaladsl
import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.persistence.journal.Tagged
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.scaladsl.CurrentEventsByTagQuery
import akka.persistence.query.{ EventEnvelope, Sequence }
import akka.persistence.query.scaladsl.{ CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery, ReadJournal }
import akka.persistence.testkit.EventStorage
import akka.persistence.testkit.internal.InMemStorageExtension
import akka.persistence.testkit.query.internal.EventsByPersistenceIdStage
import akka.stream.scaladsl.Source
import akka.util.unused
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

object PersistenceTestKitReadJournal {
  val Identifier = "akka.persistence.testkit.query"
}

final class PersistenceTestKitReadJournal(system: ExtendedActorSystem, @unused config: Config, configPath: String)
    extends ReadJournal
    with EventsByPersistenceIdQuery
    with CurrentEventsByPersistenceIdQuery
    with CurrentEventsByTagQuery {

  private val log = LoggerFactory.getLogger(getClass)

  private val storage: EventStorage = {
    // use shared path up to before `query` to identify which inmem journal we are addressing
    val storagePluginId = configPath.replaceAll("""query$""", "journal")
    log.debug("Using in memory storage [{}] for test kit read journal", storagePluginId)
    InMemStorageExtension(system).storageFor(storagePluginId)
  }

  private def unwrapTaggedPayload(payload: Any): Any = payload match {
    case Tagged(payload, _) => payload
    case payload            => payload
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
      EventEnvelope(
        Sequence(pr.sequenceNr),
        persistenceId,
        pr.sequenceNr,
        unwrapTaggedPayload(pr.payload),
        pr.timestamp,
        pr.metadata)
    }
  }

  override def currentEventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    offset match {
      case NoOffset =>
      case _ =>
        throw new UnsupportedOperationException("Offsets not supported for persistence test kit currentEventsByTag yet")
    }
    Source(storage.tryReadByTag(tag)).map { pr =>
      EventEnvelope(
        Sequence(pr.timestamp),
        pr.persistenceId,
        pr.sequenceNr,
        unwrapTaggedPayload(pr.payload),
        pr.timestamp,
        pr.metadata)
    }
  }
}
