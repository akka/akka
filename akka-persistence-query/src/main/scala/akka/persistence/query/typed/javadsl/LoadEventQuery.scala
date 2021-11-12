/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed.javadsl

import java.util.Optional
import java.util.concurrent.CompletionStage

import akka.persistence.query.javadsl.ReadJournal
import akka.persistence.query.typed.EventEnvelope

/**
 * [[EventsBySliceQuery]] that is using a timestamp based offset should also implement this query.
 */
trait LoadEventQuery extends ReadJournal {

  def loadEnvelope[Event](persistenceId: String, sequenceNr: Long): CompletionStage[Optional[EventEnvelope[Event]]]
}
