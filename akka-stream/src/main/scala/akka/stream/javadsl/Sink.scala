/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl
import akka.stream.scaladsl2

object Sink {
  /**
   * Java API
   *
   * Adapt [[scaladsl2.Sink]] for use within JavaDSL
   */
  def adapt[O](sink: scaladsl2.Sink[O]): javadsl.Sink[O] = SinkAdapter(sink)
}
/**
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
trait Sink[-In] extends javadsl.SinkOps[In]

/** INTERNAL API */
private[akka] object SinkAdapter {
  def apply[In](sink: scaladsl2.Sink[In]) = new SinkAdapter[In] { def delegate = sink }
}

/** INTERNAL API */
private[akka] abstract class SinkAdapter[-In] extends Sink[In] {

  protected def delegate: scaladsl2.Sink[In]

  /** Converts this Sink to it's Scala DSL counterpart */
  def asScala: scaladsl2.Sink[In] = delegate

  // RUN WITH //

  def runWith[T](tap: javadsl.TapWithKey[In, T], materializer: scaladsl2.FlowMaterializer): T =
    delegate.runWith(tap.asScala)(materializer).asInstanceOf[T]

  def runWith(tap: javadsl.SimpleTap[In], materializer: scaladsl2.FlowMaterializer): Unit =
    delegate.runWith(tap.asScala)(materializer)

}