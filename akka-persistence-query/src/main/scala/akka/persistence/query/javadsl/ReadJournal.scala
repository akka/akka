/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query.javadsl

import akka.persistence.query.{ Query, Hint }
import akka.stream.javadsl.Source

import scala.annotation.varargs

/**
 * Java API
 *
 * API for reading persistent events and information derived
 * from stored persistent events.
 *
 * The purpose of the API is not to enforce compatibility between different
 * journal implementations, because the technical capabilities may be very different.
 * The interface is very open so that different journals may implement specific queries.
 *
 * Usage:
 * {{{
 * final ReadJournal journal =
 *   PersistenceQuery.get(system).getReadJournalFor(queryPluginConfigPath);
 *
 * final Source&lt;EventEnvelope, ?&gt; events =
 *   journal.query(new EventsByTag("mytag", 0L));
 * }}}
 */

final class ReadJournal(backing: akka.persistence.query.scaladsl.ReadJournal) {

  /**
   * Java API
   *
   * A query that returns a `Source` with output type `T` and materialized value `M`.
   *
   * The `hints` are optional parameters that defines how to execute the
   * query, typically specific to the journal implementation.
   *
   */
  @varargs def query[T, M](q: Query[T, M], hints: Hint*): Source[T, M] =
    backing.query(q, hints: _*).asJava

}
