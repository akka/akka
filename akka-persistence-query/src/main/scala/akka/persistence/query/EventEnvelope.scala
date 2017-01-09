/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.query

/**
 * Event wrapper adding meta data for the events in the result stream of
 * [[akka.persistence.query.scaladsl.EventsByTagQuery]] query, or similar queries.
 */
final case class EventEnvelope(
  offset:        Offset,
  persistenceId: String,
  sequenceNr:    Long,
  event:         Any)
