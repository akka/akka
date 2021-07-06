/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.state.inmem.javadsl

import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._

import akka.Done
import akka.persistence.query.javadsl.DurableStateStoreQuery
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.state.javadsl.{ DurableStateUpdateStore, GetObjectResult }
import akka.stream.javadsl.Source

class InmemDurableStateStore[A] extends DurableStateUpdateStore[A] with DurableStateStoreQuery[A] {
  // TODO fix or OK?
  implicit val ec = scala.concurrent.ExecutionContext.global
  private val stateStore = new akka.persistence.query.state.inmem.scaladsl.InmemDurableStateStore[A] 
  
  def getObject(persistenceId: String): CompletionStage[GetObjectResult[A]] =
    toJava(stateStore.getObject(persistenceId).map(_.toJava))

  def upsertObject(persistenceId: String, seqNr: Long, value: A, tag: String): CompletionStage[Done] =
    toJava(stateStore.upsertObject(persistenceId, seqNr, value, tag))

  def deleteObject(persistenceId: String): CompletionStage[Done] =
    toJava(stateStore.deleteObject(persistenceId))

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A],akka.NotUsed] = {
    stateStore.changes(tag, offset).asJava
  }
  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A],akka.NotUsed] = {
    stateStore.currentChanges(tag, offset).asJava
  }
}
