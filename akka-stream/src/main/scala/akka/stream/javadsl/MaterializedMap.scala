/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl
import akka.stream.scaladsl2

/**
 * Java API
 *
 * Returned by [[RunnableFlow#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Tap` or `Drain`, e.g.
 * [[akka.stream.scaladsl2.SubscriberTap#subscriber]] or [[akka.stream.scaladsl2.PublisherDrain#publisher]].
 */
trait MaterializedMap extends javadsl.MaterializedTap with javadsl.MaterializedDrain

/** Java API */
trait MaterializedTap {
  /**
   * Retrieve a materialized `Tap`, e.g. the `Subscriber` of a [[akka.stream.scaladsl2.SubscriberTap]].
   */
  def materializedTap[T](key: javadsl.TapWithKey[_, T]): T
}

/** Java API */
trait MaterializedDrain {
  /**
   * Retrieve a materialized `Drain`, e.g. the `Publisher` of a [[akka.stream.scaladsl2.PublisherDrain]].
   */
  def materializedDrain[D](key: javadsl.DrainWithKey[_, D]): D
}

/** INTERNAL API */
private[akka] class MaterializedMapAdapter(delegate: scaladsl2.MaterializedMap) extends MaterializedMap {

  override def materializedTap[T](key: javadsl.TapWithKey[_, T]): T =
    delegate.materializedTap(key.asScala).asInstanceOf[T]

  override def materializedDrain[D](key: javadsl.DrainWithKey[_, D]): D =
    delegate.materializedDrain(key.asScala).asInstanceOf[D]
}
