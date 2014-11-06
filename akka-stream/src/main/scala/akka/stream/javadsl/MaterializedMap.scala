/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl
import akka.stream.scaladsl

/**
 * Java API
 *
 * Returned by [[RunnableFlow#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Source` or `Sink`, e.g.
 * [[akka.stream.javadsl.Source#subscriber]] or [[akka.stream.javadsl.Sink#publisher]].
 */
class MaterializedMap(delegate: scaladsl.MaterializedMap) {
  /**
   * Retrieve a materialized `Source`, e.g. the `Subscriber` of a [[akka.stream.javadsl.Source#subscriber]].
   */
  def get[T](key: javadsl.KeyedSource[_, T]): T =
    delegate.get(key.asScala).asInstanceOf[T]

  /**
   * Retrieve a materialized `Sink`, e.g. the `Publisher` of a [[akka.stream.javadsl.Sink#publisher]].
   */
  def get[D](key: javadsl.KeyedSink[_, D]): D =
    delegate.get(key.asScala).asInstanceOf[D]

}
