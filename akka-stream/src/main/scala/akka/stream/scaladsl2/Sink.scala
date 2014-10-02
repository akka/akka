/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import org.reactivestreams.Subscriber

import scala.language.implicitConversions
import scala.annotation.unchecked.uncheckedVariance

/**
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
trait Sink[-In] {
  def toSubscriber()(implicit materializer: FlowMaterializer): Subscriber[In @uncheckedVariance]
}
