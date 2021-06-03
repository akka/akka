/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.state.inmem.javadsl

import java.util.Optional
import java.util.concurrent.{ CompletionStage, ConcurrentHashMap }

import scala.concurrent.Future
import scala.compat.java8.FutureConverters._

import akka.Done

import akka.persistence.state.javadsl.{ DurableStateUpdateStore, GetObjectResult }

class InmemDurableStateStore[A] extends DurableStateUpdateStore[A] {
  val store = new ConcurrentHashMap[String, A]()

  def getObject(persistenceId: String): CompletionStage[GetObjectResult[A]] =
    toJava(Future.successful(GetObjectResult(Optional.ofNullable(store.get(persistenceId)), 0)))

  def upsertObject(persistenceId: String, seqNr: Long, value: A, tag: String): CompletionStage[Done] =
    toJava(Future.successful(store.put(persistenceId, value) match {
      case _ => Done
    }))

  def deleteObject(persistenceId: String): CompletionStage[Done] =
    toJava(Future.successful(store.remove(persistenceId) match {
      case _ => Done
    }))
}
