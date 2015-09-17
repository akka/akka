/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.scaladsl

import akka.stream.scaladsl.Source

/**
 * A plugin may optionally support this query by implementing this trait.
 */
trait CurrentPersistenceIdsQuery extends ReadJournal {

  /**
   * Same type of query as [[AllPersistenceIdsQuery#allPersistenceIds]] but the stream
   * is completed immediately when it reaches the end of the "result set". Persistent
   * actors that are created after the query is completed are not included in the stream.
   */
  def currentPersistenceIds(): Source[String, Unit]

}
