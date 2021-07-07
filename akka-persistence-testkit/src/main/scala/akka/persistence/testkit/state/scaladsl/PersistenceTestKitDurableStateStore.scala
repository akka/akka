/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.state.scaladsl

import java.util.concurrent.atomic.AtomicLong

import scala.collection.concurrent.TrieMap
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
  private val store = TrieMap.empty[String, Record[A]]

  private val (publisher, changesSource) =
    ActorSource
      .actorRef[Record[A]](PartialFunction.empty, PartialFunction.empty, 256, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

  private val EarliestOffset = 0L
  private val lastGlobalOffset = new AtomicLong(EarliestOffset)

  def getObject(persistenceId: String): Future[GetObjectResult[A]] =
    Future.successful(GetObjectResult(store.get(persistenceId).map(_.value), 0))

  def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] =
    Future.successful {
      val globalOffset = lastGlobalOffset.incrementAndGet()
      val record = Record(globalOffset, persistenceId, revision, value, tag)
      store.put(persistenceId, record)
      publisher ! record
      Done
    }

  def deleteObject(persistenceId: String): Future[Done] =
    Future.successful(store.remove(persistenceId) match {
      case _ => Done
    })

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = {
    val fromOffset = offset match {
      case NoOffset             => EarliestOffset
      case Sequence(fromOffset) => fromOffset
      case offset =>
        throw new UnsupportedOperationException(s"$offset not supported in PersistenceTestKitDurableStateStore.")
    }
    def byTagFromOffset(rec: Record[A]) = rec.tag == tag && rec.globalOffset > fromOffset
    def byTagFromOffsetNotDeleted(rec: Record[A]) = byTagFromOffset(rec) && store.contains(rec.persistenceId)

    Source(store.values.toSeq.filter(byTagFromOffset).sortBy(_.globalOffset))
      .concat(changesSource)
      .filter(byTagFromOffsetNotDeleted)
      .statefulMapConcat { () =>
        var globalOffsetSeen = EarliestOffset

        { record =>
          if (record.globalOffset > globalOffsetSeen) {
            globalOffsetSeen = record.globalOffset
            record :: Nil
          } else Nil
        }
      }
      .map(_.toDurableStateChange)
  }

  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = {
    changes(tag, offset).takeWhile(_.offset match {
      case Sequence(seqNr) => seqNr <= lastGlobalOffset.get()
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
