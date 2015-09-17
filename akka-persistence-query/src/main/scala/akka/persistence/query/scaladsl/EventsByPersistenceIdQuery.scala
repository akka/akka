/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.scaladsl

import akka.stream.scaladsl.Source
import akka.persistence.query.EventEnvelope

/**
 * A plugin may optionally support this query by implementing this trait.
 */
trait EventsByPersistenceIdQuery extends ReadJournal {

  /**
   * Query events for a specific `PersistentActor` identified by `persistenceId`.
   *
   * You can retrieve a subset of all events by specifying `fromSequenceNr` and `toSequenceNr`
   * or use `0L` and `Long.MaxValue` respectively to retrieve all events.
   *
   * The returned event stream should be ordered by sequence number.
   *
   * The stream is not completed when it reaches the end of the currently stored events,
   * but it continues to push new events when new events are persisted.
   * Corresponding query that is completed when it reaches the end of the currently
   * stored events is provided by [[CurrentEventsByPersistenceIdQuery#currentEventsByPersistenceId]].
   */
  def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long,
                            toSequenceNr: Long): Source[EventEnvelope, Unit]

}
