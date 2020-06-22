/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.inmem.scaladsl
import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.persistence.query.EventEnvelope
import akka.persistence.query.journal.inmem.internal.EventsByPersistenceIdStage
import akka.persistence.query.scaladsl.{ EventsByPersistenceIdQuery, ReadJournal }
import akka.stream.scaladsl.Source
import com.typesafe.config.Config

/**
 * Scala API [[akka.persistence.query.scaladsl.ReadJournal]] implementation for the inmem journal.
 *
 * It is retrieved with:
 * {{{
 * val queries = PersistenceQuery(system).readJournalFor[InmemReadJournal](InmemReadJournal.Identifier)
 * }}}
 *
 * Corresponding Java API is in [[akka.persistence.query.journal.inmem.javadsl.InmemReadJournal]].
 *
 * Configuration settings can be defined in the configuration section with the
 * absolute path corresponding to the identifier, which is `"akka.persistence.query.journal.inmem"`
 */
final class InmemReadJournal(system: ExtendedActorSystem, config: Config)
    extends ReadJournal
    with EventsByPersistenceIdQuery {

  private val writeJournalPluginId: String = {
    val configuredJournal = config.getString("write-plugin")
    if (configuredJournal.isEmpty) {
      system.settings.config.getString("akka.persistence.journal.plugin")
    } else {
      configuredJournal
    }
  }
  private val maxBufSize: Int = config.getInt("max-buffer-size")

  /**
   * `eventsByPersistenceId` is used for retrieving events for a specific
   * `PersistentActor` or `EventSourcedBehaviour` identified by `persistenceId`.
   *
   * You can retrieve a subset of all events by specifying `fromSequenceNr` and `toSequenceNr`
   * or use `0L` and `Long.MaxValue` respectively to retrieve all events. Note that
   * the corresponding sequence number of each event is provided in the
   * [[akka.persistence.query.EventEnvelope]], which makes it possible to resume the
   * stream at a later point from a given sequence number.
   *
   * The returned event stream is ordered by sequence number, i.e. the same order as the
   * `PersistentActor` persisted the events. The same prefix of stream elements (in same order)
   * are returned for multiple executions of the query, except for when events have been deleted.
   *
   * The stream is not completed when it reaches the end of the currently stored events,
   * but it continues to push new events when new events are persisted.
   *
   * The stream is completed with failure if there is a failure in executing the query in the
   * backend journal.
   */
  override def eventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    Source.fromGraph(
      new EventsByPersistenceIdStage(persistenceId, fromSequenceNr, toSequenceNr, maxBufSize, writeJournalPluginId))
  }
}

object InmemReadJournal {

  final val Identifier = "akka.persistence.query.journal.inmem"
}
