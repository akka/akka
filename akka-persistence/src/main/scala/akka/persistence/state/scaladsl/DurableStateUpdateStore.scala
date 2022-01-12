/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.state.scaladsl

import scala.concurrent.Future

import akka.Done

/**
 * API for updating durable state objects.
 *
 * For Java API see [[akka.persistence.state.javadsl.DurableStateUpdateStore]].
 */
trait DurableStateUpdateStore[A] extends DurableStateStore[A] {

  /**
   * @param seqNr sequence number for optimistic locking. starts at 1.
   */
  def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done]

  def deleteObject(persistenceId: String): Future[Done]

}
