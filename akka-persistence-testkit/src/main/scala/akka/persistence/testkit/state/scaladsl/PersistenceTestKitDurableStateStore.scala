/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.state.scaladsl

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.Future

import akka.Done
import akka.actor.ExtendedActorSystem
import akka.persistence.query.scaladsl.DurableStateStoreQuery
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.query.NoOffset
import akka.persistence.query.Sequence
import akka.persistence.state.scaladsl.{ DurableStateUpdateStore, GetObjectResult }
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.OverflowStrategy

object PersistenceTestKitDurableStateStore {
  val Identifier = "akka.persistence.testkit.state"
}

class PersistenceTestKitDurableStateStore[A](val system: ExtendedActorSystem)
    extends DurableStateUpdateStore[A]
    with DurableStateStoreQuery[A] {

  private implicit val sys = system
  private var store = Map.empty[String, Record[A]]

  private val (publisher, changesSource) =
    ActorSource
      .actorRef[Record[A]](PartialFunction.empty, PartialFunction.empty, 256, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

  private val EarliestOffset = 0L
  private val lastGlobalOffset = new AtomicLong(EarliestOffset)

  def getObject(persistenceId: String): Future[GetObjectResult[A]] = this.synchronized {
    Future.successful(GetObjectResult(store.get(persistenceId).map(_.value), 0))
  }

  def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] = this.synchronized {
    val globalOffset = lastGlobalOffset.incrementAndGet()
    val record = Record(globalOffset, persistenceId, revision, value, tag)
    store = store + (persistenceId -> record)
    publisher ! record
    Future.successful(Done)
  }

  def deleteObject(persistenceId: String): Future[Done] = this.synchronized {
    store = store - persistenceId
    Future.successful(Done)
  }

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = this.synchronized {
    val fromOffset = offset match {
      case NoOffset             => EarliestOffset
      case Sequence(fromOffset) => fromOffset
      case offset =>
        throw new UnsupportedOperationException(s"$offset not supported in PersistenceTestKitDurableStateStore.")
    }
    def byTagFromOffset(rec: Record[A]) = rec.tag == tag && rec.globalOffset > fromOffset
    def byTagFromOffsetNotDeleted(rec: Record[A]) = byTagFromOffset(rec) && store.contains(rec.persistenceId)

    Source(store.values.toSeq.filter(byTagFromOffset _).sortBy(_.globalOffset))
      .concat(changesSource)
      .filter(byTagFromOffsetNotDeleted _)
      .statefulMapConcat { () =>
        var globalOffsetSeen = EarliestOffset

        { record: Record[A] =>
          if (record.globalOffset > globalOffsetSeen) {
            globalOffsetSeen = record.globalOffset
            record :: Nil
          } else Nil
        }
      }
      .map(_.toDurableStateChange)
  }

  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = this.synchronized {
    changes(tag, offset).takeWhile(_.offset match {
      case Sequence(fromOffset) => fromOffset <= lastGlobalOffset.get()
      case offset =>
        throw new UnsupportedOperationException(s"$offset not supported in PersistenceTestKitDurableStateStore.")
    })
  }
}

private final case class Record[A](
    globalOffset: Long,
    persistenceId: String,
    revision: Long,
    value: A,
    tag: String,
    timestamp: Long = System.currentTimeMillis) {
  def toDurableStateChange: DurableStateChange[A] =
    new DurableStateChange(persistenceId, revision, value, Sequence(globalOffset), timestamp)
}
