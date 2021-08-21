/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.state.javadsl

import java.util.concurrent.CompletionStage

import akka.Done
import akka.annotation.ApiMayChange

/**
 * API for updating durable state objects.
 *
 * For Scala API see [[akka.persistence.state.scaladsl.DurableStateUpdateStore]].
 *
 * API May Change
 */
@ApiMayChange
trait DurableStateUpdateStore[A] extends DurableStateStore[A] {

  /**
   * @param seqNr sequence number for optimistic locking. starts at 1.
   */
  def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): CompletionStage[Done]

  def deleteObject(persistenceId: String): CompletionStage[Done]
}
