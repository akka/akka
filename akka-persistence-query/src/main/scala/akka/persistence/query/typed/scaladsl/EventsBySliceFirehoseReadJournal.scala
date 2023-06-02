/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed.scaladsl

import java.time.Instant

import scala.annotation.nowarn
import scala.collection.immutable
import scala.concurrent.Future

import com.typesafe.config.Config

import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl._
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.typed.internal.EventsBySliceFirehose
import akka.stream.scaladsl.Source

object EventsBySliceFirehoseReadJournal {
  val Identifier = "akka.persistence.query.events-by-slice-firehose"

}

@nowarn("msg=never used")
final class EventsBySliceFirehoseReadJournal(system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends ReadJournal
    with EventsBySliceQuery
    with EventTimestampQuery
    with LoadEventQuery {

  // FIXME config
  private val queryPluginId = "akka.persistence.r2dbc.query"

  private lazy val eventsBySliceQuery =
    PersistenceQuery(system)
      .readJournalFor[EventsBySliceQuery](queryPluginId)

  override def eventsBySlices[Event](
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[EventEnvelope[Event], NotUsed] = {
    EventsBySliceFirehose(system).eventsBySlices(entityType, minSlice, maxSlice, offset)
  }

  override def sliceForPersistenceId(persistenceId: String): Int =
    eventsBySliceQuery.sliceForPersistenceId(persistenceId)

  override def sliceRanges(numberOfRanges: Int): immutable.Seq[Range] =
    eventsBySliceQuery.sliceRanges(numberOfRanges)

  override def timestampOf(persistenceId: String, sequenceNr: Long): Future[Option[Instant]] =
    eventsBySliceQuery match {
      case q: EventTimestampQuery => q.timestampOf(persistenceId, sequenceNr)
      case _ =>
        throw new IllegalArgumentException(
          s"Underlying ReadJournal [$queryPluginId] doesn't implement EventTimestampQuery")
    }

  override def loadEnvelope[Event](persistenceId: String, sequenceNr: Long): Future[EventEnvelope[Event]] =
    eventsBySliceQuery match {
      case q: LoadEventQuery => q.loadEnvelope(persistenceId, sequenceNr)
      case _ =>
        throw new IllegalArgumentException(s"Underlying ReadJournal [$queryPluginId] doesn't implement LoadEventQuery")
    }
}
