/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed.scaladsl

import scala.concurrent.Future

import akka.persistence.query.scaladsl.ReadJournal
import akka.persistence.query.typed.EventEnvelope

/**
 * [[EventsBySliceQuery]] that is using a timestamp based offset should also implement this query.
 */
trait LoadEventQuery extends ReadJournal {

  def loadEnvelope[Event](persistenceId: String, sequenceNr: Long): Future[Option[EventEnvelope[Event]]]
}
