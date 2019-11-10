/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

/**
 * Used in [[akka.actor.typed.internal.routing.RoutingLogics.ConsistentHashingLogic]].
 */
trait TypedSerializer[T] {
  def apply(message: T): Array[Byte]
}
