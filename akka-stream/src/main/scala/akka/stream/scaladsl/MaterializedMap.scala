/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

/**
 * Returned by [[RunnableFlow#run]] and [[FlowGraph#run]] and can be used to retrieve the materialized
 * `Source` inputs or `Sink` outputs, e.g. [[SubscriberSource]] or [[PublisherSink]].
 */
trait MaterializedMap {

  /**
   * Retrieve a materialized key, `Source`, `Sink` or `Key`, e.g. the `Subscriber` of a [[SubscriberSource]].
   */
  def get(key: Materializable): key.MaterializedType

  /**
   * Merge two materialized maps.
   */
  def merge(otherMap: MaterializedMap): MaterializedMap

  /**
   * Update the materialized map with a new value.
   */
  def updated(key: KeyedMaterializable[_], value: Any): MaterializedMap

  /**
   * Check if this map is empty.
   */
  def isEmpty: Boolean

  /**
   * An iterator over the key value pairs in this materialized map.
   */
  def iterator: Iterator[(AnyRef, Any)]
}

object MaterializedMap {
  private val emptyInstance = MaterializedMapImpl(Map.empty)

  def empty: MaterializedMap = emptyInstance
}

/**
 * Common trait for things that have a MaterializedType.
 */
trait Materializable {
  type MaterializedType
}

/**
 * Common trait for keyed things that have a MaterializedType.
 */
trait KeyedMaterializable[M] extends Materializable {
  override type MaterializedType = M
}

/**
 * A key that is not directly tied to a sink or source instance.
 */
trait Key[M] extends KeyedMaterializable[M] {

  /**
   * Materialize the value for this key. All Sink and Source keys have been materialized and exist in the map.
   */
  def materialize(map: MaterializedMap): MaterializedType
}

private[stream] case class MaterializedMapImpl(map: Map[AnyRef, Any]) extends MaterializedMap {
  private def failure(key: KeyedMaterializable[_]) = {
    val keyType = key match {
      case _: KeyedSource[_, _] ⇒ "Source"
      case _: KeyedSink[_, _]   ⇒ "Sink"
      case _: Key[_]            ⇒ "Key"
      case _                    ⇒ "Unknown"
    }
    new IllegalArgumentException(s"$keyType key [$key] doesn't exist in this flow")
  }

  override def get(key: Materializable): key.MaterializedType = key match {
    case km: KeyedMaterializable[_] ⇒ map.get(key) match {
      case Some(v) ⇒ v.asInstanceOf[key.MaterializedType]
      case None    ⇒ throw failure(km)
    }
    case _ ⇒ ().asInstanceOf[key.MaterializedType]
  }

  override def merge(otherMap: MaterializedMap) =
    if (map.isEmpty) otherMap
    else if (otherMap.isEmpty) this
    else MaterializedMapImpl(map ++ otherMap.iterator)

  override def updated(key: KeyedMaterializable[_], value: Any) = MaterializedMapImpl(map.updated(key, value))

  override def isEmpty = map.isEmpty

  override def iterator = map.iterator
}
