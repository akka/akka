/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.annotation.DoNotInherit
import akka.annotation.ApiMayChange

/**
 * This trait is not meant to be extended by user code. If you do so, you may
 * lose binary compatibility.
 */
@DoNotInherit
@ApiMayChange
trait ActorContext[T] {
  // this should be a pure interface, i.e. only abstract methods

  /**
   * Get the `javadsl` of this `ActorContext`.
   */
  def asJava: javadsl.ActorContext[T]

  /**
   * Get the `scaladsl` of this `ActorContext`.
   */
  def asScala: scaladsl.ActorContext[T]
}

