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
  /**
   * The default identifier for [[LeveldbReadJournal]] to be used with
   * [[akka.persistence.query.PersistenceQuery#readJournalFor]].
   *
   * The value is `"akka.persistence.query.journal.leveldb"` and corresponds
   * to the absolute path to the read journal configuration entry.
   */
  final val Identifier = "akka.persistence.query.journal.leveldb"
}

/**
 * [[akka.persistence.query.scaladsl.ReadJournal]] implementation for LevelDB.
 *
 * It is retrieved with Scala API:
 * {{{
 * val queries = PersistenceQuery(system).readJournalFor(LeveldbReadJournal.Identifier)
 * }}}
 *
 * or with Java API:
 * {{{
 * ReadJournal queries =
 *   PersistenceQuery.get(system).getReadJournalFor(LeveldbReadJournal.Identifier());
 * }}}
 *
 * Configuration settings can be defined in the configuration section with the
 * absolute path corresponding to the identifier, which is `"akka.persistence.query.journal.leveldb"`
 * for the default [[LeveldbReadJournal#Identifier]]. See `reference.conf`.
 *
 * The following queries are supported.
 *
 * == EventsByPersistenceId ==
 *
 * [[akka.persistence.query.EventsByPersistenceId]] is used for retrieving events for a specific
 * `PersistentActor` identified by `persistenceId`.
 *
 * You can retrieve a subset of all events by specifying `fromSequenceNr` and `toSequenceNr`
 * or use `0L` and `Long.MaxValue` respectively to retrieve all events. Note that
 * the corresponding sequence number of each event is provided in the
 * [[akka.persistence.query.EventEnvelope]], which makes it possible to resume the
 * stream at a later point from a given sequence number.
 *
 * The returned event stream is ordered by sequence number, i.e. the same order as the
 * `PersistentActor` persisted the events. The same prefix of stream elements (in same order)
 *  are returned for multiple executions of the query, except for when events have been deleted.
 *
 * The query supports two different completion modes:
 * <ul>
 * <li>The stream is not completed when it reaches the end of the currently stored events,
 *     but it continues to push new events when new events are persisted. This is the
 *     default mode that is used when no hints are given. It can also be specified with
 *     hint [[akka.persistence.query.RefreshInterval]].</li>
 * <li>The stream is completed when it reaches the end of the currently stored events.
 *     This mode is specified with hint [[akka.persistence.query.NoRefresh]].</li>
 * </ul>
 *
 * The LevelDB write journal is notifying the query side as soon as events are persisted, but for
 * efficiency reasons the query side retrieves the events in batches that sometimes can
 * be delayed up to the configured `refresh-interval` or given [[akka.persistence.query.RefreshInterval]]
 * hint.
 *
 * The stream is completed with failure if there is a failure in executing the query in the
 * backend journal.
 *
 * == AllPersistenceIds ==
 *
 * [[akka.persistence.query.AllPersistenceIds]] is used for retrieving all `persistenceIds` of all
 * persistent actors.
 *
 * The returned event stream is unordered and you can expect different order for multiple
 * executions of the query.
 *
 * The query supports two different completion modes:
 * <ul>
 * <li>The stream is not completed when it reaches the end of the currently used `persistenceIds`,
 *     but it continues to push new `persistenceIds` when new persistent actors are created.
 *     This is the default mode that is used when no hints are given. It can also be specified with
 *     hint [[akka.persistence.query.RefreshInterval]].</li>
 * <li>The stream is completed when it reaches the end of the currently used `persistenceIds`.
 *     This mode is specified with hint [[akka.persistence.query.NoRefresh]].</li>
 * </ul>
 *
 * The LevelDB write journal is notifying the query side as soon as new `persistenceIds` are
 * created and there is no periodic polling or batching involved in this query.
 *
 * The stream is completed with failure if there is a failure in executing the query in the
 * backend journal.
 *
 * == EventsByTag ==
 *
 * [[akka.persistence.query.EventsByTag]] is used for retrieving events that were marked with
 * a given tag, e.g. all events of an Aggregate Root type.
 *
 * To tag events you create an [[akka.persistence.journal.EventAdapter]] that wraps the events
 * in a [[akka.persistence.journal.leveldb.Tagged]] with the given `tags`.
 *
 * You can retrieve a subset of all events by specifying `offset`, or use `0L` to retrieve all
 * events with a given tag. The `offset` corresponds to an ordered sequence number for
 * the specific tag. Note that the corresponding offset of each event is provided in the
 * [[akka.persistence.query.EventEnvelope]], which makes it possible to resume the
 * stream at a later point from a given offset.
 *
 * In addition to the `offset` the `EventEnvelope` also provides `persistenceId` and `sequenceNr`
 * for each event. The `sequenceNr` is the sequence number for the persistent actor with the
 * `persistenceId` that persisted the event. The `persistenceId` + `sequenceNr` is an unique
 * identifier for the event.
 *
 * The returned event stream is ordered by the offset (tag sequence number), which corresponds
 * to the same order as the write journal stored the events. The same stream elements (in same order)
 * are returned for multiple executions of the query. Deleted events are not deleted from the
 * tagged event stream.
 *
 * The query supports two different completion modes:
 * <ul>
 * <li>The stream is not completed when it reaches the end of the currently stored events,
 *     but it continues to push new events when new events are persisted. This is the
 *     default mode that is used when no hints are given. It can also be specified with
 *     hint [[akka.persistence.query.RefreshInterval]].</li>
 * <li>The stream is completed when it reaches the end of the currently stored events.
 *     This mode is specified with hint [[akka.persistence.query.NoRefresh]].</li>
 * </ul>
 *
 * The LevelDB write journal is notifying the query side as soon as tagged events are persisted, but for
 * efficiency reasons the query side retrieves the events in batches that sometimes can
 * be delayed up to the configured `refresh-interval` or given [[akka.persistence.query.RefreshInterval]]
 * hint.
 *
 * The stream is completed with failure if there is a failure in executing the query in the
 * backend journal.
 */
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

