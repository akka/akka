/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl
import akka.stream.scaladsl
import scala.collection.JavaConverters.asJavaIteratorConverter

/**
 * Java API
 *
 * Returned by [[RunnableFlow#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Source` or `Sink`, e.g.
 * [[akka.stream.javadsl.Source#subscriber]] or [[akka.stream.javadsl.Sink#publisher]].
 */
class MaterializedMap(delegate: scaladsl.MaterializedMap) {
  def asScala: scaladsl.MaterializedMap = delegate

  /**
   * Retrieve a materialized key, `Source`, `Sink` or `Key`, e.g. the `Subscriber` of a
   * [[akka.stream.javadsl.Source#subscriber]].
   */
  def get[T](key: javadsl.KeyedMaterializable[T]): T =
    delegate.get(key.asScala)

  /**
   * Merge two materialized maps.
   */
  def merge(otherMap: MaterializedMap): MaterializedMap =
    if (this.isEmpty) otherMap
    else if (otherMap.isEmpty) this
    else new MaterializedMap(this.asScala.merge(otherMap.asScala))

  /**
   * Update the materialized map with a new value.
   */
  def updated(key: KeyedMaterializable[_], value: Object): MaterializedMap =
    new MaterializedMap(delegate.updated(key.asScala, value))

  /**
   * Check if this map is empty.
   */
  def isEmpty: Boolean = delegate.isEmpty

  /**
   * An iterator over the key value pairs in this materialized map.
   */
  def iterator: java.util.Iterator[akka.japi.Pair[Object, Object]] = {
    delegate.iterator.map { case (a, b) ⇒ new akka.japi.Pair(a.asInstanceOf[Object], b.asInstanceOf[Object]) } asJava
  }
}

/**
 * Java API
 *
 * Common interface for keyed things that can be materialized.
 */
trait KeyedMaterializable[M] {
  def asScala: scaladsl.KeyedMaterializable[M]
}

/**
 * Java API
 *
 * A key that is not directly tied to a sink or source instance.
 */
class Key[M](delegate: scaladsl.Key[M]) extends KeyedMaterializable[M] {
  def asScala: scaladsl.Key[M] = delegate

  /**
   * Materialize the value for this key. All Sink and Source keys have been materialized and exist in the map.
   */
  def materialize(map: MaterializedMap): Object = delegate.materialize(map.asScala).asInstanceOf[Object]
}
