/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import scala.annotation.tailrec

import akka.cluster.Cluster
import akka.cluster.UniqueAddress
import akka.util.HashCode

object ORSet {
  private val _empty: ORSet[Any] = new ORSet(Map.empty, VersionVector.empty)
  def empty[A]: ORSet[A] = _empty.asInstanceOf[ORSet[A]]
  def apply(): ORSet[Any] = _empty
  /**
   * Java API
   */
  def create[A](): ORSet[A] = empty[A]

  /**
   * Extract the [[ORSet#elements]].
   */
  def unapply[A](s: ORSet[A]): Option[Set[A]] = Some(s.elements)

  /**
   * Extract the [[ORSet#elements]] of an `ORSet`.
   */
  def unapply(a: ReplicatedData): Option[Set[Any]] = a match {
    case s: ORSet[Any] @unchecked ⇒ Some(s.elements)
    case _                        ⇒ None
  }

  /**
   * INTERNAL API
   */
  private[akka]type Dot = VersionVector

  /**
   * INTERNAL API
   * Subtract the `vvector` from the `dot`.
   * What this means is that any (node, version) pair in
   * `dot` that is &lt;= an entry in `vvector` is removed from `dot`.
   * Example [{a, 3}, {b, 2}, {d, 14}, {g, 22}] -
   *         [{a, 4}, {b, 1}, {c, 1}, {d, 14}, {e, 5}, {f, 2}] =
   *         [{b, 2}, {g, 22}]
   */
  private[akka] def subtractDots(dot: Dot, vvector: VersionVector): Dot = {

    @tailrec def dropDots(remaining: List[(UniqueAddress, Long)], acc: List[(UniqueAddress, Long)]): List[(UniqueAddress, Long)] =
      remaining match {
        case Nil ⇒ acc
        case (d @ (node, v1)) :: rest ⇒
          val v2 = vvector.versionAt(node)
          if (v2 >= v1)
            // dot is dominated by version vector, drop it
            dropDots(rest, acc)
          else
            dropDots(rest, d :: acc)
      }

    if (dot.isEmpty)
      VersionVector.empty
    else {
      dot match {
        case OneVersionVector(node, v1) ⇒
          // if dot is dominated by version vector, drop it
          if (vvector.versionAt(node) >= v1) VersionVector.empty
          else dot

        case ManyVersionVector(vs) ⇒
          val remaining = vs.toList
          val newDots = dropDots(remaining, Nil)
          VersionVector(newDots)
      }
    }
  }

  /**
   * INTERNAL API
   * @see [[ORSet#merge]]
   */
  private[akka] def mergeCommonKeys[A](commonKeys: Set[A], lhs: ORSet[A], rhs: ORSet[A]): Map[A, ORSet.Dot] =
    mergeCommonKeys(commonKeys.iterator, lhs, rhs)

  private def mergeCommonKeys[A](commonKeys: Iterator[A], lhs: ORSet[A], rhs: ORSet[A]): Map[A, ORSet.Dot] = {
    commonKeys.foldLeft(Map.empty[A, ORSet.Dot]) {
      case (acc, k) ⇒
        val lhsDots = lhs.elementsMap(k)
        val rhsDots = rhs.elementsMap(k)
        (lhsDots, rhsDots) match {
          case (OneVersionVector(n1, v1), OneVersionVector(n2, v2)) ⇒
            if (n1 == n2 && v1 == v2)
              // one single common dot
              acc.updated(k, lhsDots)
            else {
              // no common, lhsUniqueDots == lhsDots, rhsUniqueDots == rhsDots
              val lhsKeep = ORSet.subtractDots(lhsDots, rhs.vvector)
              val rhsKeep = ORSet.subtractDots(rhsDots, lhs.vvector)
              val merged = lhsKeep.merge(rhsKeep)
              // Perfectly possible that an item in both sets should be dropped
              if (merged.isEmpty) acc
              else acc.updated(k, merged)
            }
          case (ManyVersionVector(lhsVs), ManyVersionVector(rhsVs)) ⇒
            val commonDots = lhsVs.filter {
              case (thisDotNode, v) ⇒ rhsVs.get(thisDotNode).exists(_ == v)
            }
            val commonDotsKeys = commonDots.keys
            val lhsUniqueDots = lhsVs -- commonDotsKeys
            val rhsUniqueDots = rhsVs -- commonDotsKeys
            val lhsKeep = ORSet.subtractDots(VersionVector(lhsUniqueDots), rhs.vvector)
            val rhsKeep = ORSet.subtractDots(VersionVector(rhsUniqueDots), lhs.vvector)
            val merged = lhsKeep.merge(rhsKeep).merge(VersionVector(commonDots))
            // Perfectly possible that an item in both sets should be dropped
            if (merged.isEmpty) acc
            else acc.updated(k, merged)
          case (ManyVersionVector(lhsVs), OneVersionVector(n2, v2)) ⇒
            val commonDots = lhsVs.filter {
              case (n1, v1) ⇒ v1 == v2 && n1 == n2
            }
            val commonDotsKeys = commonDots.keys
            val lhsUniqueDots = lhsVs -- commonDotsKeys
            val rhsUnique = if (commonDotsKeys.isEmpty) rhsDots else VersionVector.empty
            val lhsKeep = ORSet.subtractDots(VersionVector(lhsUniqueDots), rhs.vvector)
            val rhsKeep = ORSet.subtractDots(rhsUnique, lhs.vvector)
            val merged = lhsKeep.merge(rhsKeep).merge(VersionVector(commonDots))
            // Perfectly possible that an item in both sets should be dropped
            if (merged.isEmpty) acc
            else acc.updated(k, merged)
          case (OneVersionVector(n1, v1), ManyVersionVector(rhsVs)) ⇒
            val commonDots = rhsVs.filter {
              case (n2, v2) ⇒ v1 == v2 && n1 == n2
            }
            val commonDotsKeys = commonDots.keys
            val lhsUnique = if (commonDotsKeys.isEmpty) lhsDots else VersionVector.empty
            val rhsUniqueDots = rhsVs -- commonDotsKeys
            val lhsKeep = ORSet.subtractDots(lhsUnique, rhs.vvector)
            val rhsKeep = ORSet.subtractDots(VersionVector(rhsUniqueDots), lhs.vvector)
            val merged = lhsKeep.merge(rhsKeep).merge(VersionVector(commonDots))
            // Perfectly possible that an item in both sets should be dropped
            if (merged.isEmpty) acc
            else acc.updated(k, merged)
        }
    }
  }

  /**
   * INTERNAL API
   * @see [[ORSet#merge]]
   */
  private[akka] def mergeDisjointKeys[A](keys: Set[A], elementsMap: Map[A, ORSet.Dot], vvector: VersionVector,
                                         accumulator: Map[A, ORSet.Dot]): Map[A, ORSet.Dot] =
    mergeDisjointKeys(keys.iterator, elementsMap, vvector, accumulator)

  private def mergeDisjointKeys[A](keys: Iterator[A], elementsMap: Map[A, ORSet.Dot], vvector: VersionVector,
                                   accumulator: Map[A, ORSet.Dot]): Map[A, ORSet.Dot] = {
    keys.foldLeft(accumulator) {
      case (acc, k) ⇒
        val dots = elementsMap(k)
        if (vvector > dots || vvector == dots)
          acc
        else {
          // Optimise the set of stored dots to include only those unseen
          val newDots = subtractDots(dots, vvector)
          acc.updated(k, newDots)
        }
    }
  }
}

/**
 * Implements a 'Observed Remove Set' CRDT, also called a 'OR-Set'.
 * Elements can be added and removed any number of times. Concurrent add wins
 * over remove.
 *
 * It is not implemented as in the paper
 * <a href="http://hal.upmc.fr/file/index/docid/555588/filename/techreport.pdf">A comprehensive study of Convergent and Commutative Replicated Data Types</a>.
 * This is more space efficient and doesn't accumulate garbage for removed elements.
 * It is described in the paper
 * <a href="https://hal.inria.fr/file/index/docid/738680/filename/RR-8083.pdf">An optimized conflict-free replicated set</a>
 * The implementation is inspired by the Riak DT <a href="https://github.com/basho/riak_dt/blob/develop/src/riak_dt_orswot.erl">
 * riak_dt_orswot</a>.
 *
 * The ORSet has a version vector that is incremented when an element is added to
 * the set. The `node -&gt; count` pair for that increment is stored against the
 * element as its "birth dot". Every time the element is re-added to the set,
 * its "birth dot" is updated to that of the `node -&gt; count` version vector entry
 * resulting from the add. When an element is removed, we simply drop it, no tombstones.
 *
 * When an element exists in replica A and not replica B, is it because A added
 * it and B has not yet seen that, or that B removed it and A has not yet seen that?
 * In this implementation we compare the `dot` of the present element to the version vector
 * in the Set it is absent from. If the element dot is not "seen" by the Set version vector,
 * that means the other set has yet to see this add, and the item is in the merged
 * Set. If the Set version vector dominates the dot, that means the other Set has removed this
 * element already, and the item is not in the merged Set.
 *
 * This class is immutable, i.e. "modifying" methods return a new instance.
 */
@SerialVersionUID(1L)
final class ORSet[A] private[akka] (
  private[akka] val elementsMap: Map[A, ORSet.Dot],
  private[akka] val vvector:     VersionVector)
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning with FastMerge {

  type T = ORSet[A]

  /**
   * Scala API
   */
  def elements: Set[A] = elementsMap.keySet

  /**
   * Java API
   */
  def getElements(): java.util.Set[A] = {
    import scala.collection.JavaConverters._
    elements.asJava
  }

  def contains(a: A): Boolean = elementsMap.contains(a)

  def isEmpty: Boolean = elementsMap.isEmpty

  def size: Int = elementsMap.size

  /**
   * Adds an element to the set
   */
  def +(element: A)(implicit node: Cluster): ORSet[A] = add(node, element)

  /**
   * Adds an element to the set
   */
  def add(node: Cluster, element: A): ORSet[A] = add(node.selfUniqueAddress, element)

  /**
   * INTERNAL API
   */
  private[akka] def add(node: UniqueAddress, element: A): ORSet[A] = {
    val newVvector = vvector + node
    val newDot = VersionVector(node, newVvector.versionAt(node))
    assignAncestor(new ORSet(elementsMap = elementsMap.updated(element, newDot), vvector = newVvector))
  }

  /**
   * Removes an element from the set.
   */
  def -(element: A)(implicit node: Cluster): ORSet[A] = remove(node, element)

  /**
   * Removes an element from the set.
   */
  def remove(node: Cluster, element: A): ORSet[A] = remove(node.selfUniqueAddress, element)

  /**
   * INTERNAL API
   */
  private[akka] def remove(node: UniqueAddress, element: A): ORSet[A] =
    assignAncestor(copy(elementsMap = elementsMap - element))

  /**
   * Removes all elements from the set, but keeps the history.
   * This has the same result as using [[#remove]] for each
   * element, but it is more efficient.
   */
  def clear(node: Cluster): ORSet[A] = clear(node.selfUniqueAddress)

  /**
   * INTERNAL API
   */
  private[akka] def clear(node: UniqueAddress): ORSet[A] =
    assignAncestor(copy(elementsMap = Map.empty))

  /**
   * When element is in this Set but not in that Set:
   * Compare the "birth dot" of the present element to the version vector in the Set it is absent from.
   * If the element dot is not "seen" by other Set version vector, that means the other set has yet to
   * see this add, and the element is to be in the merged Set.
   * If the other Set version vector dominates the dot, that means the other Set has removed
   * the element already, and the element is not to be in the merged Set.
   *
   * When element in both this Set and in that Set:
   * Some dots may still need to be shed. If this Set has dots that the other Set does not have,
   * and the other Set version vector dominates those dots, then we need to drop those dots.
   * Keep only common dots, and dots that are not dominated by the other sides version vector
   */
  override def merge(that: ORSet[A]): ORSet[A] = {
    if ((this eq that) || that.isAncestorOf(this)) this.clearAncestor()
    else if (this.isAncestorOf(that)) that.clearAncestor()
    else {
      val commonKeys =
        if (this.elementsMap.size < that.elementsMap.size)
          this.elementsMap.keysIterator.filter(that.elementsMap.contains)
        else
          that.elementsMap.keysIterator.filter(this.elementsMap.contains)
      val entries00 = ORSet.mergeCommonKeys(commonKeys, this, that)
      val thisUniqueKeys = this.elementsMap.keysIterator.filterNot(that.elementsMap.contains)
      val entries0 = ORSet.mergeDisjointKeys(thisUniqueKeys, this.elementsMap, that.vvector, entries00)
      val thatUniqueKeys = that.elementsMap.keysIterator.filterNot(this.elementsMap.contains)
      val entries = ORSet.mergeDisjointKeys(thatUniqueKeys, that.elementsMap, this.vvector, entries0)
      val mergedVvector = this.vvector.merge(that.vvector)

      clearAncestor()
      new ORSet(entries, mergedVvector)
    }
  }

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    vvector.needPruningFrom(removedNode)

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): ORSet[A] = {
    val pruned = elementsMap.foldLeft(Map.empty[A, ORSet.Dot]) {
      case (acc, (elem, dot)) ⇒
        if (dot.needPruningFrom(removedNode)) acc.updated(elem, dot.prune(removedNode, collapseInto))
        else acc
    }
    if (pruned.isEmpty)
      copy(vvector = vvector.prune(removedNode, collapseInto))
    else {
      // re-add elements that were pruned, to bump dots to right vvector
      val newSet = new ORSet(elementsMap = elementsMap ++ pruned, vvector = vvector.prune(removedNode, collapseInto))
      pruned.keys.foldLeft(newSet) {
        case (s, elem) ⇒ s.add(collapseInto, elem)
      }
    }
  }

  override def pruningCleanup(removedNode: UniqueAddress): ORSet[A] = {
    val updated = elementsMap.foldLeft(elementsMap) {
      case (acc, (elem, dot)) ⇒
        if (dot.needPruningFrom(removedNode)) acc.updated(elem, dot.pruningCleanup(removedNode))
        else acc
    }
    new ORSet(updated, vvector.pruningCleanup(removedNode))
  }

  private def copy(elementsMap: Map[A, ORSet.Dot] = this.elementsMap, vvector: VersionVector = this.vvector): ORSet[A] =
    new ORSet(elementsMap, vvector)

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String = s"OR$elements"

  override def equals(o: Any): Boolean = o match {
    case other: ORSet[_] ⇒ vvector == other.vvector && elementsMap == other.elementsMap
    case _               ⇒ false
  }

  override def hashCode: Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, elementsMap)
    result = HashCode.hash(result, vvector)
    result
  }
}

object ORSetKey {
  def create[A](id: String): Key[ORSet[A]] = ORSetKey(id)
}

@SerialVersionUID(1L)
final case class ORSetKey[A](_id: String) extends Key[ORSet[A]](_id) with ReplicatedDataSerialization
