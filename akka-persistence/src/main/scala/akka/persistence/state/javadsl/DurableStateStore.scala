/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.state.javadsl

import java.util.Optional
import java.util.concurrent.CompletionStage

/**
 * API for reading durable state objects.
 *
 * For Scala API see [[akka.persistence.state.scaladsl.DurableStateStore]].
 *
 * See also [[DurableStateUpdateStore]]
 */
trait DurableStateStore[A] {

  def getObject(persistenceId: String): CompletionStage[GetObjectResult[A]]

}

final case class GetObjectResult[A](value: Optional[A], revision: Long)
