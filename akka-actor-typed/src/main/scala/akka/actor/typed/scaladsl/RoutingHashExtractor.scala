/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.nio.charset.StandardCharsets

object RoutingHashExtractor {

  /**
   * Convenience function for creating string hash keys for Consistent Hashing.
   *
   * See [[akka.actor.typed.RoutingHashExtractor]]
   */
  def stringExtractor[T](f: T => String): akka.actor.typed.RoutingHashExtractor[T] =
    f(_).getBytes(StandardCharsets.UTF_8)

}
