/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import scala.collection.immutable.TreeMap

// =============================================================================================
// Adapted from HashRing.scala in Debasish Ghosh's Redis Client, licensed under Apache 2 license
// =============================================================================================

/**
 * Consistent Hashing node ring abstraction.
 *
 * A good explanation of Consistent Hashing:
 * http://weblogs.java.net/blog/tomwhite/archive/2007/11/consistent_hash.html
 *
 * Note that toString of the ring nodes are used for the node
 * hash, i.e. make sure it is different for different nodes.
 *
 */
class ConsistentHash[T] private (nodeRing: TreeMap[Int, T], virtualNodesFactor: Int) {

  import ConsistentHash._

  if (virtualNodesFactor < 1) throw new IllegalArgumentException("virtualNodesFactor must be >= 1")

  /**
   * Adds a node to the node ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   */
  def :+(node: T): ConsistentHash[T] =
    new ConsistentHash(nodeRing ++ ((1 to virtualNodesFactor) map { r ⇒ (nodeHashFor(node, r) -> node) }), virtualNodesFactor)

  /**
   * Adds a node to the node ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   * JAVA API
   */
  def add(node: T): ConsistentHash[T] = this :+ node

  /**
   * Removes a node from the node ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   */
  def :-(node: T): ConsistentHash[T] =
    new ConsistentHash(nodeRing -- ((1 to virtualNodesFactor) map { r ⇒ nodeHashFor(node, r) }), virtualNodesFactor)

  /**
   * Removes a node from the node ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   * JAVA API
   */
  def remove(node: T): ConsistentHash[T] = this :- node

  /**
   * Get the node responsible for the data key.
   * Can only be used if nodes exists in the node ring,
   * otherwise throws `IllegalStateException`
   */
  def nodeFor(key: Array[Byte]): T = {
    if (isEmpty) throw new IllegalStateException("Can't get node for [%s] from an empty node ring" format key)
    val hash = hashFor(key)
    // find the next node clockwise in the nodeRing, pick first if end of tree
    nodeRing.rangeImpl(Some(hash), None).headOption.getOrElse(nodeRing.head)._2
  }

  /**
   * Is the node ring empty, i.e. no nodes added or all removed.
   */
  def isEmpty: Boolean = nodeRing.isEmpty

}

object ConsistentHash {
  def apply[T](nodes: Iterable[T], virtualNodesFactor: Int) = {
    new ConsistentHash(TreeMap.empty[Int, T] ++
      (for (node ← nodes; vnode ← 1 to virtualNodesFactor) yield (nodeHashFor(node, vnode) -> node)),
      virtualNodesFactor)
  }

  /**
   * Factory method to create a ConsistentHash
   * JAVA API
   */
  def create[T](nodes: java.lang.Iterable[T], virtualNodesFactor: Int) = {
    import scala.collection.JavaConverters._
    apply(nodes.asScala, virtualNodesFactor)
  }

  private def nodeHashFor(node: Any, vnode: Int): Int =
    hashFor((node + ":" + vnode).getBytes("UTF-8"))

  private def hashFor(bytes: Array[Byte]): Int = MurmurHash.arrayHash(bytes)
}

