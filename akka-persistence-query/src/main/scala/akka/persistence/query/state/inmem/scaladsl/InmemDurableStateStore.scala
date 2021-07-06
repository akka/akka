/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.state.inmem.scaladsl

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

import akka.Done
import akka.persistence.query.scaladsl.DurableStateStoreQuery
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.query.NoOffset
import akka.persistence.query.Sequence
import akka.persistence.state.scaladsl.{ DurableStateUpdateStore, GetObjectResult }
import akka.stream.scaladsl.Source

class InmemDurableStateStore[A] extends DurableStateUpdateStore[A] with DurableStateStoreQuery[A] {
  private val store = new TrieMap[String, Record[A]]()

  def getObject(persistenceId: String): Future[GetObjectResult[A]] =
    Future.successful(GetObjectResult(store.get(persistenceId).map(_.value), 0))

  def upsertObject(persistenceId: String, seqNr: Long, value: A, tag: String): Future[Done] =
    Future.successful(store.put(persistenceId, Record(persistenceId, seqNr, value, tag)) match {
      case _ => Done
    })

  def deleteObject(persistenceId: String): Future[Done] =
    Future.successful(store.remove(persistenceId) match {
      case _ => Done
    })

  def changesSeq(tag: String, offset: Offset): Seq[DurableStateChange[A]] = {
    offset match {
      case NoOffset => 
        store.values.toSeq
          .filter(_.tag == tag)
          .sortBy(_.seqNr)
          .map(_.toDurableStateChange) 
      case Sequence(seqNr) => 
        store.values.toSeq
          .filter(rec => rec.tag == tag && rec.seqNr >= seqNr)
          .sortBy(_.seqNr)
          .map(_.toDurableStateChange)  
      case offset => throw new UnsupportedOperationException(s"$offset not supported.") 
    }
  }
  
  def changes(tag: String, offset: Offset): Source[DurableStateChange[A],akka.NotUsed] = {
    // TODO fix, should not terminate. requires more changes to the in-mem representation.
    Source(changesSeq(tag, offset))
  }

  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A],akka.NotUsed] = {
    Source(changesSeq(tag, offset))
  }
}

private final case class Record[A](persistenceId: String, seqNr: Long, value: A, tag: String, timestamp: Long = System.currentTimeMillis) {
  def toDurableStateChange: DurableStateChange[A] = new DurableStateChange(
    persistenceId,
    seqNr,
    value,
    Sequence(seqNr),
    timestamp)
}
