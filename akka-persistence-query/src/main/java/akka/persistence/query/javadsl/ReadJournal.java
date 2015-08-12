/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query.javadsl;

import akka.persistence.query.Query;
import akka.persistence.query.Hint;
import akka.stream.javadsl.Source;
import scala.annotation.varargs;

/**
 * Java API
 * <p>
 * API for reading persistent events and information derived
 * from stored persistent events.
 * <p>
 * The purpose of the API is not to enforce compatibility between different
 * journal implementations, because the technical capabilities may be very different.
 * The interface is very open so that different journals may implement specific queries.
 * <p>
 * Usage:
 * <pre><code>
 * final ReadJournal journal =
 *   PersistenceQuery.get(system).getReadJournalFor(queryPluginConfigPath);
 *
 * final Source&lt;EventEnvelope, ?&gt; events =
 *   journal.query(new EventsByTag("mytag", 0L));
 * </code></pre>
 */

public interface ReadJournal {

  /**
   * Java API
   * <p>
   * A query that returns a `Source` with output type `T` and materialized value `M`.
   * <p>
   * The `hints` are optional parameters that defines how to execute the
   * query, typically specific to the journal implementation.
   *
   */
  <T, M> Source<T, M> query(Query<T, M> q, Hint... hints);

}
