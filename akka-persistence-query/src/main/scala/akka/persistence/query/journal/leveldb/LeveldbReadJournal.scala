/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.journal.leveldb

import scala.concurrent.duration._
import akka.actor.ExtendedActorSystem
import akka.persistence.query.EventsByPersistenceId
import akka.persistence.query.Hint
import akka.persistence.query.Query
import akka.persistence.query.scaladsl
import akka.serialization.SerializationExtension
import akka.stream.scaladsl.Source
import scala.concurrent.duration.FiniteDuration
import akka.persistence.query.NoRefresh
import akka.persistence.query.RefreshInterval
import com.typesafe.config.Config
import akka.persistence.query.EventEnvelope
import akka.persistence.query.AllPersistenceIds
import akka.persistence.query.EventsByTag
import akka.util.ByteString
import java.net.URLEncoder

object LeveldbReadJournal {
  final val Identifier = "akka.persistence.query.journal.leveldb"
}

class LeveldbReadJournal(system: ExtendedActorSystem, config: Config) extends scaladsl.ReadJournal {

  private val serialization = SerializationExtension(system)
  private val defaulRefreshInterval: Option[FiniteDuration] =
    Some(config.getDuration("refresh-interval", MILLISECONDS).millis)
  private val writeJournalPluginId: String = config.getString("write-plugin")
  private val maxBufSize: Int = config.getInt("max-buffer-size")

  override def query[T, M](q: Query[T, M], hints: Hint*): Source[T, M] = q match {
    case EventsByPersistenceId(pid, from, to) ⇒ eventsByPersistenceId(pid, from, to, hints)
    case AllPersistenceIds                    ⇒ allPersistenceIds(hints)
    case EventsByTag(tag, offset)             ⇒ eventsByTag(tag, offset, hints)
    case unknown                              ⇒ unsupportedQueryType(unknown)
  }

  def eventsByPersistenceId(persistenceId: String, fromSeqNr: Long, toSeqNr: Long, hints: Seq[Hint]): Source[EventEnvelope, Unit] = {
    Source.actorPublisher[EventEnvelope](EventsByPersistenceIdPublisher.props(persistenceId, fromSeqNr, toSeqNr,
      refreshInterval(hints), maxBufSize, writeJournalPluginId)).mapMaterializedValue(_ ⇒ ())
      .named("eventsByPersistenceId-" + persistenceId)
  }

  def allPersistenceIds(hints: Seq[Hint]): Source[String, Unit] = {
    // no polling for this query, the write journal will push all changes, but
    // we still use the `NoRefresh` hint as user API
    val liveQuery = refreshInterval(hints).isDefined
    Source.actorPublisher[String](AllPersistenceIdsPublisher.props(liveQuery, maxBufSize, writeJournalPluginId))
      .mapMaterializedValue(_ ⇒ ())
      .named("allPersistenceIds")
  }

  def eventsByTag(tag: String, offset: Long, hints: Seq[Hint]): Source[EventEnvelope, Unit] = {
    Source.actorPublisher[EventEnvelope](EventsByTagPublisher.props(tag, offset, Long.MaxValue,
      refreshInterval(hints), maxBufSize, writeJournalPluginId)).mapMaterializedValue(_ ⇒ ())
      .named("eventsByTag-" + URLEncoder.encode(tag, ByteString.UTF_8))
  }

  private def refreshInterval(hints: Seq[Hint]): Option[FiniteDuration] =
    if (hints.contains(NoRefresh))
      None
    else
      hints.collectFirst { case RefreshInterval(interval) ⇒ interval }.orElse(defaulRefreshInterval)

  private def unsupportedQueryType[M, T](unknown: Query[T, M]): Nothing =
    throw new IllegalArgumentException(s"${getClass.getSimpleName} does not implement the ${unknown.getClass.getName} query type!")
}

