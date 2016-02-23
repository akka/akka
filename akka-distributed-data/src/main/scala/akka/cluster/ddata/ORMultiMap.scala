/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.{ UniqueAddress, Cluster }

object ORMultiMap {

  val _empty: ORMultiMap[Any] = new ORMultiMap(ORMap.empty)
  /**
   * Provides an empty multimap.
   */
  def empty[A]: ORMultiMap[A] = _empty.asInstanceOf[ORMultiMap[A]]
  def apply(): ORMultiMap[Any] = _empty

  /**
   * Java API
   */
  def create[A](): ORMultiMap[A] = empty[A]

  /**
   * Extract the [[ORMultiMap#entries]].
   */
  def unapply[A](m: ORMultiMap[A]): Option[Map[String, Set[A]]] = Some(m.entries)

  /**
   * Extract the [[ORMultiMap#entries]] of an `ORMultiMap`.
   */
  def unapply(value: Any): Option[Map[String, Set[Any]]] = value match {
    case m: ORMultiMap[Any] @unchecked ⇒ Some(m.entries)
    case _                             ⇒ None
  }
}

/**
 * An immutable multi-map implementation. This class wraps an
 * [[ORMap]] with an [[ORSet]] for the map's value.
 *
 * This class is immutable, i.e. "modifying" methods return a new instance.
 */
@SerialVersionUID(1L)
final class ORMultiMap[A] private[akka] (private[akka] val underlying: ORMap[ORSet[A]])
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  override type T = ORMultiMap[A]

  override def merge(that: T): T =
    new ORMultiMap(underlying.merge(that.underlying))

  /**
   * Scala API: All entries of a multimap where keys are strings and values are sets.
   */
  def entries: Map[String, Set[A]] =
    underlying.entries.map { case (k, v) ⇒ k -> v.elements }

  /**
   * Java API: All entries of a multimap where keys are strings and values are sets.
   */
  def getEntries(): java.util.Map[String, java.util.Set[A]] = {
    import scala.collection.JavaConverters._
    val result = new java.util.HashMap[String, java.util.Set[A]]
    underlying.entries.foreach {
      case (k, v) ⇒ result.put(k, v.elements.asJava)
    }
    result
  }

  /**
   * Get the set associated with the key if there is one.
   */
  def get(key: String): Option[Set[A]] =
    underlying.get(key).map(_.elements)

  /**
   * Scala API: Get the set associated with the key if there is one,
   * else return the given default.
   */
  def getOrElse(key: String, default: ⇒ Set[A]): Set[A] =
    get(key).getOrElse(default)

  def contains(key: String): Boolean = underlying.contains(key)

  def isEmpty: Boolean = underlying.isEmpty

  def size: Int = underlying.size

  /**
   * Convenience for put. Requires an implicit Cluster.
   * @see [[#put]]
   */
  def +(entry: (String, Set[A]))(implicit node: Cluster): ORMultiMap[A] = {
    val (key, value) = entry
    put(node, key, value)
  }

  /**
   * Scala API: Associate an entire set with the key while retaining the history of the previous
   * replicated data set.
   */
  def put(node: Cluster, key: String, value: Set[A]): ORMultiMap[A] =
    put(node.selfUniqueAddress, key, value)

  /**
   * Java API: Associate an entire set with the key while retaining the history of the previous
   * replicated data set.
   */
  def put(node: Cluster, key: String, value: java.util.Set[A]): ORMultiMap[A] = {
    import scala.collection.JavaConverters._
    put(node, key, value.asScala.toSet)
  }

  /**
   * INTERNAL API
   */
  private[akka] def put(node: UniqueAddress, key: String, value: Set[A]): ORMultiMap[A] = {
    val newUnderlying = underlying.updated(node, key, ORSet.empty[A]) { existing ⇒
      value.foldLeft(existing.clear(node)) { (s, element) ⇒ s.add(node, element) }
    }
    new ORMultiMap(newUnderlying)
  }

  /**
   * Convenience for remove. Requires an implicit Cluster.
   * @see [[#remove]]
   */
  def -(key: String)(implicit node: Cluster): ORMultiMap[A] =
    remove(node, key)

  /**
   * Remove an entire set associated with the key.
   */
  def remove(node: Cluster, key: String): ORMultiMap[A] =
    remove(node.selfUniqueAddress, key)

  /**
   * INTERNAL API
   */
  private[akka] def remove(node: UniqueAddress, key: String): ORMultiMap[A] =
    new ORMultiMap(underlying.remove(node, key))

  /**
   * Scala API: Add an element to a set associated with a key. If there is no existing set then one will be initialised.
   */
  def addBinding(key: String, element: A)(implicit node: Cluster): ORMultiMap[A] =
    addBinding(node.selfUniqueAddress, key, element)

  /**
   * Java API: Add an element to a set associated with a key. If there is no existing set then one will be initialised.
   */
  def addBinding(node: Cluster, key: String, element: A): ORMultiMap[A] =
    addBinding(key, element)(node)

  /**
   * INTERNAL API
   */
  private[akka] def addBinding(node: UniqueAddress, key: String, element: A): ORMultiMap[A] = {
    val newUnderlying = underlying.updated(node, key, ORSet.empty[A])(_.add(node, element))
    new ORMultiMap(newUnderlying)
  }

  /**
   * Scala API: Remove an element of a set associated with a key. If there are no more elements in the set then the
   * entire set will be removed.
   */
  def removeBinding(key: String, element: A)(implicit node: Cluster): ORMultiMap[A] =
    removeBinding(node.selfUniqueAddress, key, element)

  /**
   * Java API: Remove an element of a set associated with a key. If there are no more elements in the set then the
   * entire set will be removed.
   */
  def removeBinding(node: Cluster, key: String, element: A): ORMultiMap[A] =
    removeBinding(key, element)(node)

  /**
   * INTERNAL API
   */
  private[akka] def removeBinding(node: UniqueAddress, key: String, element: A): ORMultiMap[A] = {
    val newUnderlying = {
      val u = underlying.updated(node, key, ORSet.empty[A])(_.remove(node, element))
      u.get(key) match {
        case Some(s) if s.isEmpty ⇒ u.remove(node, key)
        case _                    ⇒ u
      }
    }
    new ORMultiMap(newUnderlying)
  }

  /**
   * Replace an element of a set associated with a key with a new one if it is different. This is useful when an element is removed
   * and another one is added within the same Update. The order of addition and removal is important in order
   * to retain history for replicated data.
   */
  def replaceBinding(key: String, oldElement: A, newElement: A)(implicit node: Cluster): ORMultiMap[A] =
    replaceBinding(node.selfUniqueAddress, key, oldElement, newElement)

  /**
   * INTERNAL API
   */
  private[akka] def replaceBinding(node: UniqueAddress, key: String, oldElement: A, newElement: A): ORMultiMap[A] =
    if (newElement != oldElement)
      addBinding(node, key, newElement).removeBinding(node, key, oldElement)
    else
      this

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    underlying.needPruningFrom(removedNode)

  override def pruningCleanup(removedNode: UniqueAddress): T =
    new ORMultiMap(underlying.pruningCleanup(removedNode))

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): T =
    new ORMultiMap(underlying.prune(removedNode, collapseInto))

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String = s"ORMulti$entries"

  override def equals(o: Any): Boolean = o match {
    case other: ORMultiMap[_] ⇒ underlying == other.underlying
    case _                    ⇒ false
  }

  override def hashCode: Int = underlying.hashCode
}

object ORMultiMapKey {
  def create[A](id: String): Key[ORMultiMap[A]] = ORMultiMapKey(id)
}

@SerialVersionUID(1L)
final case class ORMultiMapKey[A](_id: String) extends Key[ORMultiMap[A]](_id) with ReplicatedDataSerialization
