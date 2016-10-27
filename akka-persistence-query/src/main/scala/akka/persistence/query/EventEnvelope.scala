/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.query

/**
 * Event wrapper adding meta data for the events in the result stream of
 * [[akka.persistence.query.scaladsl.EventsByTagQuery]] query, or similar queries.
 */
final case class EventEnvelope(
  offset:        Long,
  persistenceId: String,
  sequenceNr:    Long,
  event:         Any)

/**
 * Event wrapper adding meta data for the events in the result stream of
 * [[akka.persistence.query.scaladsl.EventsByTagQuery2]] query, or similar queries.
 */
// TODO: Rename it to EventEnvelope in Akka 2.5
final case class EventEnvelope2(
  offset:        Offset,
  persistenceId: String,
  sequenceNr:    Long,
  event:         Any)
