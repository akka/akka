/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import org.reactivestreams.Subscriber

import scala.concurrent.Future
import scala.language.implicitConversions
import scala.annotation.unchecked.uncheckedVariance

/**
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
trait Sink[-In] {
  /**
   * Connect this `Sink` to a `Tap` and run it. The returned value is the materialized value
   * of the `Tap`, e.g. the `Subscriber` of a [[SubscriberTap]].
   */
  def runWith(tap: TapWithKey[In])(implicit materializer: FlowMaterializer): tap.MaterializedType
}

object Sink {
  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def apply[T](subscriber: Subscriber[T]): Drain[T] = SubscriberDrain(subscriber)
}
