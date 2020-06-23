/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query.javadsl
import akka.NotUsed
import akka.persistence.query.EventEnvelope
import akka.persistence.query.javadsl.ReadJournal
import akka.persistence.query.javadsl.EventsByPersistenceIdQuery
import akka.stream.javadsl.Source
import akka.persistence.testkit.query.scaladsl

final class PersistenceTestKitReadJournal(delegate: scaladsl.PersistenceTestKitReadJournal)
    extends ReadJournal
    with EventsByPersistenceIdQuery {

  override def eventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
    delegate.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).asJava
}
