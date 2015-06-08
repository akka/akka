/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query.scaladsl

import akka.persistence.query.{ Hint, Query }
import akka.stream.scaladsl.Source

/**
 * API for reading persistent events and information derived
 * from stored persistent events.
 *
 * The purpose of the API is not to enforce compatibility between different
 * journal implementations, because the technical capabilities may be very different.
 * The interface is very open so that different journals may implement specific queries.
 *
 * Usage:
 * {{{
 * val journal = PersistenceQuery(system).readJournalFor(queryPluginConfigPath)
 * val events = journal.query(EventsByTag("mytag", 0L))
 * }}}
 *
 * For Java API see [[akka.persistence.query.javadsl.ReadJournal]].
 */
abstract class ReadJournal {

  /**
   * A query that returns a `Source` with output type `T` and materialized
   * value `M`.
   *
   * The `hints` are optional parameters that defines how to execute the
   * query, typically specific to the journal implementation.
   *
   */
  def query[T, M](q: Query[T, M], hints: Hint*): Source[T, M]

}
