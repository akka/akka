/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.query.javadsl

import akka.NotUsed
import akka.persistence.query.{ EventEnvelope, Offset }
import akka.stream.javadsl.Source

/**
 * A plugin may optionally support this query by implementing this interface.
 */
trait CurrentEventsByTagQuery extends ReadJournal {

  /**
   * Same type of query as [[EventsByTagQuery#eventsByTag]] but the event stream
   * is completed immediately when it reaches the end of the "result set". Events that are
   * stored after the query is completed are not included in the event stream.
   */
  def currentEventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed]
}

