/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.state.javadsl

import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._
import akka.Done
import akka.persistence.query.javadsl.DurableStateStoreQuery
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.state.javadsl.{ DurableStateUpdateStore, GetObjectResult }
import akka.persistence.testkit.state.scaladsl.{ PersistenceTestKitDurableStateStore => SStore }
import akka.stream.javadsl.Source

object PersistenceTestKitDurableStateStore {
  val Identifier = akka.persistence.testkit.state.scaladsl.PersistenceTestKitDurableStateStore.Identifier
}

class PersistenceTestKitDurableStateStore[A](stateStore: SStore[A])
    extends DurableStateUpdateStore[A]
    with DurableStateStoreQuery[A] {

  def getObject(persistenceId: String): CompletionStage[GetObjectResult[A]] =
    toJava(stateStore.getObject(persistenceId).map(_.toJava)(stateStore.system.dispatcher))

  def upsertObject(persistenceId: String, seqNr: Long, value: A, tag: String): CompletionStage[Done] =
    toJava(stateStore.upsertObject(persistenceId, seqNr, value, tag))

  def deleteObject(persistenceId: String): CompletionStage[Done] =
    toJava(stateStore.deleteObject(persistenceId))

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = {
    stateStore.changes(tag, offset).asJava
  }
  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = {
    stateStore.currentChanges(tag, offset).asJava
  }
}
