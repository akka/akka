/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.state.inmem.scaladsl

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

import akka.Done
import akka.persistence.state.scaladsl.{ DurableStateUpdateStore, GetObjectResult }

class InmemDurableStateStore[A] extends DurableStateUpdateStore[A] {
  val store = new TrieMap[String, A]()

  def getObject(persistenceId: String): Future[GetObjectResult[A]] =
    Future.successful(GetObjectResult(store.get(persistenceId), 0))

  def upsertObject(persistenceId: String, seqNr: Long, value: A, tag: String): Future[Done] =
    Future.successful(store.put(persistenceId, value) match {
      case _ => Done
    })

  def deleteObject(persistenceId: String): Future[Done] =
    Future.successful(store.remove(persistenceId) match {
      case _ => Done
    })
}
