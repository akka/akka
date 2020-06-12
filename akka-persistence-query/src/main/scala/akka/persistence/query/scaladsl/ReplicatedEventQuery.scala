/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.scaladsl
import akka.NotUsed
import akka.persistence.query.EventEnvelope
import akka.stream.scaladsl.Source

/**
 * ReadJournal implementation may optionally support querying for
 * replicated events by implementing this trait.
 */
trait ReplicatedEventQuery extends ReadJournal {

  /**
   * Query replicated events for a `ReplicatedEntity` (`persistenceId`) from given data center.
   *
   * The returned event stream is ordered by sequence number.
   *
   * The stream is not completed when it reaches the end of the currently stored events,
   * but it continues to push new events when new events are persisted/replicated.
   */
  def replicatedEvents(persistenceId: String, fromReplica: String, sequenceNr: Long): Source[EventEnvelope, NotUsed]
  // FIXME, we need a control for speculative repliation
  // FIXME, needs to store / retrieve a ReplicatedEventEnvelope with origin info etc
  // or does it?? we know which DC it is coming from via the query

}
