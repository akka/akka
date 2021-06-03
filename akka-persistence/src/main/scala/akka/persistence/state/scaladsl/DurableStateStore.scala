/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.state.scaladsl

import scala.concurrent.Future

/**
 * API for reading durable state objects.
 *
 * For Java API see [[akka.persistence.state.javadsl.DurableStateStore]].
 *
 * See also [[DurableStateUpdateStore]]
 */
trait DurableStateStore[A] {

  def getObject(persistenceId: String): Future[GetObjectResult[A]]

}

final case class GetObjectResult[A](value: Option[A], seqNr: Long)
