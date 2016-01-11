/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.reactivestreams.Publisher

/**
 * Strategy that defines how a stream of streams should be flattened into a stream of simple elements.
 */
abstract class FlattenStrategy[-T, U]

object FlattenStrategy {

  /**
   * Strategy that flattens a stream of streams by concatenating them. This means taking an incoming stream
   * emitting its elements directly to the output until it completes and then taking the next stream. This has the
   * consequence that if one of the input stream is infinite, no other streams after that will be consumed from.
   */
  @deprecated("This is old API, instead use APIs using Source (akka.stream.scaladsl2 / akka.stream.javadsl)", since = "0.9")
  def concat[T]: FlattenStrategy[Publisher[T], T] = Concat[T]()

  @deprecated("This is old API, instead use APIs using Source (akka.stream.scaladsl2 / akka.stream.javadsl)", since = "0.9")
  private[akka] case class Concat[T]() extends FlattenStrategy[Publisher[T], T]
}