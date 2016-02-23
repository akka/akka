/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

object GSet {
  private val _empty: GSet[Any] = new GSet(Set.empty)
  def empty[A]: GSet[A] = _empty.asInstanceOf[GSet[A]]
  def apply(): GSet[Any] = _empty
  /**
   * Java API
   */
  def create[A](): GSet[A] = empty[A]

  // unapply from case class
}

/**
 * Implements a 'Add Set' CRDT, also called a 'G-Set'. You can't
 * remove elements of a G-Set.
 *
 * It is described in the paper
 * <a href="http://hal.upmc.fr/file/index/docid/555588/filename/techreport.pdf">A comprehensive study of Convergent and Commutative Replicated Data Types</a>.
 *
 * A G-Set doesn't accumulate any garbage apart from the elements themselves.
 *
 * This class is immutable, i.e. "modifying" methods return a new instance.
 */
@SerialVersionUID(1L)
final case class GSet[A](elements: Set[A]) extends ReplicatedData with ReplicatedDataSerialization with FastMerge {

  type T = GSet[A]

  /**
   * Java API
   */
  def getElements(): java.util.Set[A] = {
    import scala.collection.JavaConverters._
    elements.asJava
  }

  def contains(a: A): Boolean = elements(a)

  def isEmpty: Boolean = elements.isEmpty

  def size: Int = elements.size

  /**
   * Adds an element to the set
   */
  def +(element: A): GSet[A] = add(element)

  /**
   * Adds an element to the set
   */
  def add(element: A): GSet[A] = assignAncestor(copy(elements + element))

  override def merge(that: GSet[A]): GSet[A] =
    if ((this eq that) || that.isAncestorOf(this)) this.clearAncestor()
    else if (this.isAncestorOf(that)) that.clearAncestor()
    else {
      clearAncestor()
      copy(elements union that.elements)
    }
}

object GSetKey {
  def create[A](id: String): Key[GSet[A]] = GSetKey(id)
}

@SerialVersionUID(1L)
final case class GSetKey[A](_id: String) extends Key[GSet[A]](_id) with ReplicatedDataSerialization
