/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.state.scaladsl

import scala.concurrent.Future
import akka.Done
import scala.annotation.nowarn

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

  @deprecated(message = "Use the deleteObject overload with revision instead.", since = "2.6.20")
  def deleteObject(@nowarn persistenceId: String): Future[Done] = Future.successful(Done)

  @nowarn
  def deleteObject(persistenceId: String, @nowarn revision: Long): Future[Done] = deleteObject(persistenceId)
}
