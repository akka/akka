/*
 * Copyright (C) 2021-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.state.scaladsl

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.Future

import akka.{ Done, NotUsed }
import akka.actor.ExtendedActorSystem
import akka.persistence.Persistence
import akka.persistence.query.{
  DeletedDurableState,
  DurableStateChange,
  NoOffset,
  Offset,
  Sequence,
  UpdatedDurableState
}
import akka.persistence.query.scaladsl.{ DurableStateStorePagedPersistenceIdsQuery, DurableStateStoreQuery }
import akka.persistence.query.typed.scaladsl.DurableStateStoreBySliceQuery
import akka.persistence.state.scaladsl.{ DurableStateUpdateStore, GetObjectResult }
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.OverflowStrategy
import scala.collection.immutable

object PersistenceTestKitDurableStateStore {
  val Identifier = "akka.persistence.testkit.state"
}

class PersistenceTestKitDurableStateStore[A](val system: ExtendedActorSystem)
    extends DurableStateUpdateStore[A]
    with DurableStateStoreQuery[A]
    with DurableStateStoreBySliceQuery[A]
    with DurableStateStorePagedPersistenceIdsQuery[A] {

  private implicit val sys: ExtendedActorSystem = system
  private val persistence = Persistence(system)
  private var store = Map.empty[String, Record[A]]

  private val (publisher, changesSource) =
    ActorSource
      .actorRef[Record[A]](PartialFunction.empty, PartialFunction.empty, 256, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

  private val EarliestOffset = 0L
  private val lastGlobalOffset = new AtomicLong(EarliestOffset)

  override def getObject(persistenceId: String): Future[GetObjectResult[A]] = this.synchronized {
    Future.successful(store.get(persistenceId) match {
      case Some(Record(_, _, revision, Some(value), _, _)) => GetObjectResult(Some(value), revision)
      case Some(Record(_, _, revision, None, _, _))        => GetObjectResult(None, revision)
      case None                                            => GetObjectResult(None, 0)
    })
  }

  override def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] =
    this.synchronized {
      val globalOffset = lastGlobalOffset.incrementAndGet()
      val record = Record(globalOffset, persistenceId, revision, Some(value), tag)
      store = store + (persistenceId -> record)
      publisher ! record
      Future.successful(Done)
    }

  override def deleteObject(persistenceId: String): Future[Done] = Future.successful(Done)

  override def deleteObject(persistenceId: String, revision: Long): Future[Done] = this.synchronized {
    store = store.get(persistenceId) match {
      case Some(record) => store + (persistenceId -> record.copy(value = None, revision = revision))
      case None         => store
    }

    Future.successful(Done)
  }

  private def storeContains(persistenceId: String): Boolean = this.synchronized {
    store.contains(persistenceId)
  }

  override def changes(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = this.synchronized {
    val fromOffset = offset match {
      case NoOffset             => EarliestOffset
      case Sequence(fromOffset) => fromOffset
      case offset =>
        throw new UnsupportedOperationException(s"$offset not supported in PersistenceTestKitDurableStateStore.")
    }
    def byTagFromOffset(rec: Record[A]) = rec.tag == tag && rec.globalOffset > fromOffset
    def byTagFromOffsetNotDeleted(rec: Record[A]) = byTagFromOffset(rec) && storeContains(rec.persistenceId)

    Source(store.values.toVector.filter(byTagFromOffset).sortBy(_.globalOffset))
      .concat(changesSource)
      .filter(byTagFromOffsetNotDeleted)
      .statefulMapConcat { () =>
        var globalOffsetSeen = EarliestOffset

        { (record: Record[A]) =>
          if (record.globalOffset > globalOffsetSeen) {
            globalOffsetSeen = record.globalOffset
            record :: Nil
          } else Nil
        }
      }
      .map(_.toDurableStateChange)
  }

  override def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] =
    this.synchronized {
      val currentGlobalOffset = lastGlobalOffset.get()
      changes(tag, offset).takeWhile(_.offset match {
        case Sequence(fromOffset) =>
          fromOffset < currentGlobalOffset
        case offset =>
          throw new UnsupportedOperationException(s"$offset not supported in PersistenceTestKitDurableStateStore.")
      }, inclusive = true)
    }

  override def currentChangesBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed] =
    this.synchronized {
      val currentGlobalOffset = lastGlobalOffset.get()
      changesBySlices(entityType, minSlice, maxSlice, offset).takeWhile(_.offset match {
        case Sequence(fromOffset) =>
          fromOffset < currentGlobalOffset
        case offset =>
          throw new UnsupportedOperationException(s"$offset not supported in PersistenceTestKitDurableStateStore.")
      }, inclusive = true)
    }

  override def changesBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed] =
    this.synchronized {
      val fromOffset = offset match {
        case NoOffset             => EarliestOffset
        case Sequence(fromOffset) => fromOffset
        case offset =>
          throw new UnsupportedOperationException(s"$offset not supported in PersistenceTestKitDurableStateStore.")
      }
      def bySliceFromOffset(rec: Record[A]) = {
        val slice = persistence.sliceForPersistenceId(rec.persistenceId)
        PersistenceId.extractEntityType(rec.persistenceId) == entityType && slice >= minSlice && slice <= maxSlice && rec.globalOffset > fromOffset
      }
      def bySliceFromOffsetNotDeleted(rec: Record[A]) =
        bySliceFromOffset(rec) && storeContains(rec.persistenceId)

      Source(store.values.toVector.filter(bySliceFromOffset).sortBy(_.globalOffset))
        .concat(changesSource)
        .filter(bySliceFromOffsetNotDeleted)
        .statefulMapConcat { () =>
          var globalOffsetSeen = EarliestOffset

          { (record: Record[A]) =>
            if (record.globalOffset > globalOffsetSeen) {
              globalOffsetSeen = record.globalOffset
              record :: Nil
            } else Nil
          }
        }
        .map(_.toDurableStateChange)
    }

  override def sliceForPersistenceId(persistenceId: String): Int =
    persistence.sliceForPersistenceId(persistenceId)

  override def sliceRanges(numberOfRanges: Int): immutable.Seq[Range] =
    persistence.sliceRanges(numberOfRanges)

  override def currentPersistenceIds(afterId: Option[String], limit: Long): Source[String, NotUsed] =
    this.synchronized {
      if (limit < 1) {
        throw new IllegalArgumentException("Limit must be greater than 0")
      }
      val allKeys = store.keys.toVector.sorted
      val keys = afterId match {
        case Some(id) => allKeys.dropWhile(_ <= id)
        case None     => allKeys
      }

      // Enforce limit in Akka Streams so that we can pass long values to take as is.
      Source(keys).take(limit)
    }

}

private final case class Record[A](
    globalOffset: Long,
    persistenceId: String,
    revision: Long,
    value: Option[A],
    tag: String,
    timestamp: Long = System.currentTimeMillis) {
  def toDurableStateChange: DurableStateChange[A] = {
    value match {
      case Some(v) =>
        new UpdatedDurableState(persistenceId, revision, v, Sequence(globalOffset), timestamp)
      case None =>
        new DeletedDurableState(persistenceId, revision, Sequence(globalOffset), timestamp)
    }
  }
}
