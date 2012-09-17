/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import scala.collection.immutable.SortedMap
import scala.reflect.ClassTag
import java.util.Arrays

/**
 * Consistent Hashing node ring implementation.
 *
 * A good explanation of Consistent Hashing:
 * http://weblogs.java.net/blog/tomwhite/archive/2007/11/consistent_hash.html
 *
 * Note that toString of the ring nodes are used for the node
 * hash, i.e. make sure it is different for different nodes.
 *
 */
class ConsistentHash[T: ClassTag] private (nodes: SortedMap[Int, T], virtualNodesFactor: Int) {

  import ConsistentHash._

  if (virtualNodesFactor < 1) throw new IllegalArgumentException("virtualNodesFactor must be >= 1")

  // arrays for fast binary search and access
  // nodeHashRing is the sorted hash values of the nodes
  // nodeRing is the nodes sorted in the same order as nodeHashRing, i.e. same index
  private val (nodeHashRing: Array[Int], nodeRing: Array[T]) = {
    val (nhr: Seq[Int], nr: Seq[T]) = nodes.toSeq.unzip
    (nhr.toArray, nr.toArray)
  }

  /**
   * Adds a node to the node ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   */
  def :+(node: T): ConsistentHash[T] =
    new ConsistentHash(nodes ++ ((1 to virtualNodesFactor) map { r ⇒ (nodeHashFor(node, r) -> node) }), virtualNodesFactor)

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
    new ConsistentHash(nodes -- ((1 to virtualNodesFactor) map { r ⇒ nodeHashFor(node, r) }), virtualNodesFactor)

  /**
   * Removes a node from the node ring.
   * Note that the instance is immutable and this
   * operation returns a new instance.
   * JAVA API
   */
  def remove(node: T): ConsistentHash[T] = this :- node

  // converts the result of Arrays.binarySearch into a index in the nodeRing array
  // see documentation of Arrays.binarySearch for what it returns
  private def idx(i: Int): Int = {
    if (i >= 0) i // exact match
    else {
      val j = math.abs(i + 1)
      if (j >= nodeHashRing.length) 0 // after last, use first
      else j // next node clockwise
    }
  }

  /**
   * Get the node responsible for the data key.
   * Can only be used if nodes exists in the node ring,
   * otherwise throws `IllegalStateException`
   */
  def nodeFor(key: Array[Byte]): T = {
    if (isEmpty) throw new IllegalStateException("Can't get node for [%s] from an empty node ring" format key)

    nodeRing(idx(Arrays.binarySearch(nodeHashRing, hashFor(key))))
  }

  /**
   * Get the node responsible for the data key.
   * Can only be used if nodes exists in the node ring,
   * otherwise throws `IllegalStateException`
   */
  def nodeFor(key: String): T = {
    if (isEmpty) throw new IllegalStateException("Can't get node for [%s] from an empty node ring" format key)

    nodeRing(idx(Arrays.binarySearch(nodeHashRing, hashFor(key))))
  }

  /**
   * Is the node ring empty, i.e. no nodes added or all removed.
   */
  def isEmpty: Boolean = nodes.isEmpty

}

object ConsistentHash {
  def apply[T: ClassTag](nodes: Iterable[T], virtualNodesFactor: Int): ConsistentHash[T] = {
    new ConsistentHash(SortedMap.empty[Int, T] ++
      (for (node ← nodes; vnode ← 1 to virtualNodesFactor) yield (nodeHashFor(node, vnode) -> node)),
      virtualNodesFactor)
  }

  /**
   * Factory method to create a ConsistentHash
   * JAVA API
   */
  def create[T](nodes: java.lang.Iterable[T], virtualNodesFactor: Int): ConsistentHash[T] = {
    import scala.collection.JavaConverters._
    apply(nodes.asScala, virtualNodesFactor)(ClassTag(classOf[Any].asInstanceOf[Class[T]]))
  }

  private def nodeHashFor(node: Any, vnode: Int): Int =
    hashFor((node + ":" + vnode).getBytes("UTF-8"))

  private def hashFor(bytes: Array[Byte]): Int = MurmurHash.arrayHash(bytes)

  private def hashFor(string: String): Int = MurmurHash.stringHash(string)
}
