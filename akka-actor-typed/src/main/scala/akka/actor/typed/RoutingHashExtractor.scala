/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

/**
 * Used in [[akka.actor.typed.javadsl.Routers]] and [[akka.actor.typed.scaladsl.Routers]].
 *
 * Implementations of this class will be used in consistent hashing process. Result of this operation
 * should possibly uniquely distinguish messages.
 */
@FunctionalInterface
trait RoutingHashExtractor[T] {

  def apply(message: T): String

}
