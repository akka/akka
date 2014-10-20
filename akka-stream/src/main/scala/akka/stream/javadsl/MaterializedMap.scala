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
 * accessor method to retrieve the materialized `Source` or `Sink`, e.g.
 * [[akka.stream.scaladsl2.SubscriberSource#subscriber]] or [[akka.stream.scaladsl2.PublisherSink#publisher]].
 */
class MaterializedMap(delegate: scaladsl2.MaterializedMap) {
  /**
   * Retrieve a materialized `Source`, e.g. the `Subscriber` of a [[akka.stream.scaladsl2.SubscriberSource]].
   */
  def get[T](key: javadsl.KeyedSource[_, T]): T =
    delegate.get(key.asScala).asInstanceOf[T]

  /**
   * Retrieve a materialized `Sink`, e.g. the `Publisher` of a [[akka.stream.scaladsl2.PublisherSink]].
   */
  def get[D](key: javadsl.KeyedSink[_, D]): D =
    delegate.get(key.asScala).asInstanceOf[D]

}
