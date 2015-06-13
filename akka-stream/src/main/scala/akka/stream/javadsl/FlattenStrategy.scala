/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.scaladsl

/**
 * Strategy that defines how a stream of streams should be flattened into a stream of simple elements.
 */
abstract class FlattenStrategy[-S, T] extends scaladsl.FlattenStrategy[S, T]

object FlattenStrategy {

  /**
   * Strategy that flattens a stream of streams by concatenating them. This means taking an incoming stream
   * emitting its elements directly to the output until it completes and then taking the next stream. This has the
   * consequence that if one of the input stream is infinite, no other streams after that will be consumed from.
   */
  def concat[T, U]: FlattenStrategy[Source[T, U], T] = Concat.asInstanceOf[FlattenStrategy[Source[T, U], T]]
  /**
   * INTERNAL API
   */
  private[akka] final case object Concat extends FlattenStrategy[Any, Nothing]
}
