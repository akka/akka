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
class ConsistentHash[T] private (replicas: Int, ring: TreeMap[Int, T]) {

  import ConsistentHash._

  if (replicas < 1) throw new IllegalArgumentException("replicas must be >= 1")

  /**
   * Adds a node to the ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   */
  def :+(node: T): ConsistentHash[T] = {
    new ConsistentHash(replicas, ring ++ ((1 to replicas) map { r ⇒ (nodeHashFor(node, r) -> node) }))
  }

  /**
   * Adds a node to the ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   * JAVA API
   */
  def add(node: T): ConsistentHash[T] = this :+ node

  /**
   * Removes a node from the ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   */
  def :-(node: T): ConsistentHash[T] = {
    new ConsistentHash(replicas, ring -- ((1 to replicas) map { r ⇒ nodeHashFor(node, r) }))
  }

  /**
   * Removes a node from the ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   * JAVA API
   */
  def remove(node: T): ConsistentHash[T] = this :- node

  /**
   * Get the node responsible for the data key.
   * Can only be used if nodes exists in the ring,
   * otherwise throws `IllegalStateException`
   */
  def nodeFor(key: Array[Byte]): T = {
    if (isEmpty) throw new IllegalStateException("Can't get node for [%s] from an empty ring" format key)
    val hash = hashFor(key)
    def nextClockwise: T = {
      val (ringKey, node) = ring.rangeImpl(Some(hash), None).headOption.getOrElse(ring.head)
      node
    }
    ring.getOrElse(hash, nextClockwise)
  }

  /**
   * Is the ring empty, i.e. no nodes added or all removed.
   */
  def isEmpty: Boolean = ring.isEmpty

}

object ConsistentHash {
  def apply[T](nodes: Iterable[T], replicas: Int) = {
    new ConsistentHash(replicas, TreeMap.empty[Int, T] ++
      (for (node ← nodes; replica ← 1 to replicas) yield (nodeHashFor(node, replica) -> node)))
  }

  /**
   * Factory method to create a ConsistentHash
   * JAVA API
   */
  def create[T](nodes: java.lang.Iterable[T], replicas: Int) = {
    import scala.collection.JavaConverters._
    apply(nodes.asScala, replicas)
  }

  private def nodeHashFor(node: Any, replica: Int): Int = {
    hashFor((node + ":" + replica).getBytes("UTF-8"))
  }

  private def hashFor(bytes: Array[Byte]): Int = {
    val hash = MurmurHash.arrayHash(bytes)
    if (hash == Int.MinValue) hash + 1
    math.abs(hash)
  }
}

