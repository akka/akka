/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.inmem.javadsl
import akka.NotUsed
import akka.persistence.query.EventEnvelope
import akka.persistence.query.javadsl.{ EventsByPersistenceIdQuery, ReadJournal }
import akka.stream.javadsl.Source
import akka.persistence.query.journal.inmem.scaladsl

/**
 * Java API: [[akka.persistence.query.javadsl.ReadJournal]] implementation for in mem journal.
 *
 *
 * It is retrieved with:
 * {{{
 * InmemReadJournal queries =
 *   PersistenceQuery.get(system).getReadJournalFor(InmemReadJournal.class, InmemReadJournal.Identifier());
 * }}}
 *
 * Corresponding Scala API is in [[akka.persistence.query.journal.inmem.scaladsl.InmemReadJournal]].
 *
 * Configuration settings can be defined in the configuration section with the
 * absolute path corresponding to the identifier, which is `"akka.persistence.query.journal.inmem"`
 *
 */
final class InmemReadJournal(delegate: scaladsl.InmemReadJournal) extends ReadJournal with EventsByPersistenceIdQuery {

  override def eventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
    delegate.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).asJava
}

object InmemReadJournal {
  final val Identifier = "akka.persistence.query.journal.inmem"
}
