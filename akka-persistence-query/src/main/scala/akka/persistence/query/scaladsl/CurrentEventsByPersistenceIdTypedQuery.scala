/*
 * Copyright (C) 2015-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.scaladsl

import akka.NotUsed
import akka.annotation.ApiMayChange
import akka.persistence.query.typed.EventEnvelope
import akka.stream.scaladsl.Source

/**
 * A plugin may optionally support this query by implementing this trait.
 */
@ApiMayChange
trait CurrentEventsByPersistenceIdTypedQuery extends ReadJournal {

  /**
   * Same type of query as [[EventsByPersistenceIdTypedQuery#eventsByPersistenceIdTyped]]
   * but the event stream is completed immediately when it reaches the end of
   * the "result set". Events that are stored after the query is completed are
   * not included in the event stream.
   *
   * This is a new version of the [[EventsByPersistenceIdQuery#currentEventsByPersistenceId]] using a new
   * envelope type [[akka.persistence.query.typed.EventEnvelope]].
   *
   * @tparam Event the type of the event payload
   */
  def currentEventsByPersistenceIdTyped[Event](
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope[Event], NotUsed]

}
