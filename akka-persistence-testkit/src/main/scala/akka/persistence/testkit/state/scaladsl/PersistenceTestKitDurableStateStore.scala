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
import akka.stream.OverflowStrategy

// TODO test
class PersistenceTestKitDurableStateStore[A](val system: ExtendedActorSystem)
    extends DurableStateUpdateStore[A]
    with DurableStateStoreQuery[A] {
  private implicit val sys = system
  private val store = new TrieMap[String, Record[A]]()
  private val producer = Source.queue[Record[A]](1024, OverflowStrategy.dropHead)
  private val graph = producer.toMat(BroadcastHub.sink(bufferSize = 1024))(Keep.both)
  // Test of this is ok?
  private val (queue, _) = graph.run()
  private val earliestOffset = 0L
  private val lastGlobalOffset = new AtomicLong(earliestOffset)

  def getObject(persistenceId: String): Future[GetObjectResult[A]] =
    Future.successful(GetObjectResult(store.get(persistenceId).map(_.value), 0))

  def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] =
    Future.successful {
      val globalOffset = lastGlobalOffset.incrementAndGet()
      val record = Record(globalOffset, persistenceId, revision, value, tag)
      store.put(persistenceId, record)
      queue.offer(record)
      Done
    }

  def deleteObject(persistenceId: String): Future[Done] =
    Future.successful(store.remove(persistenceId) match {
      case _ => Done
    })

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = {
    val fromOffset = offset match {
      case NoOffset        => earliestOffset
      case Sequence(fromOffset) => fromOffset
      case offset =>
        throw new UnsupportedOperationException(s"$offset not supported in PersistenceTestKitDurableStateStore.")
    }
    // TODO Test if this is ok
    val (_, source) = graph.run()
    source
      .filter(rec => rec.tag == tag && rec.globalOffset > fromOffset && store.contains(rec.persistenceId))
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
