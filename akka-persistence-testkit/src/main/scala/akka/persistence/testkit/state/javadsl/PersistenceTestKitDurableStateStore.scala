/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.state.javadsl

import java.util.Optional
import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import akka.{ Done, NotUsed }
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.query.javadsl.{ CurrentDurableStatePersistenceIdsQuery, DurableStateStoreQuery }
import akka.persistence.state.javadsl.DurableStateUpdateStore
import akka.persistence.state.javadsl.GetObjectResult
import akka.persistence.testkit.state.scaladsl.{ PersistenceTestKitDurableStateStore => SStore }
import akka.stream.javadsl.Source

object PersistenceTestKitDurableStateStore {
  val Identifier = akka.persistence.testkit.state.scaladsl.PersistenceTestKitDurableStateStore.Identifier
}

class PersistenceTestKitDurableStateStore[A](stateStore: SStore[A])
    extends DurableStateUpdateStore[A]
    with DurableStateStoreQuery[A]
    with CurrentDurableStatePersistenceIdsQuery[A] {

  def getObject(persistenceId: String): CompletionStage[GetObjectResult[A]] =
    stateStore.getObject(persistenceId).map(_.toJava)(stateStore.system.dispatcher).toJava

  def upsertObject(persistenceId: String, seqNr: Long, value: A, tag: String): CompletionStage[Done] =
    stateStore.upsertObject(persistenceId, seqNr, value, tag).toJava

  def deleteObject(persistenceId: String): CompletionStage[Done] =
    stateStore.deleteObject(persistenceId).toJava

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = {
    stateStore.changes(tag, offset).asJava
  }
  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A], akka.NotUsed] = {
    stateStore.currentChanges(tag, offset).asJava
  }
  override def currentPersistenceIds(afterId: Optional[String], limit: Long): Source[String, NotUsed] =
    stateStore.currentPersistenceIds(afterId.asScala, limit).asJava
}
