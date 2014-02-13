/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

object TwoPhaseSet {
  val empty: TwoPhaseSet = new TwoPhaseSet
  def apply(): TwoPhaseSet = empty

  def unapply(value: Any): Option[Set[Any]] = value match {
    case s: TwoPhaseSet ⇒ Some(s.value)
    case _              ⇒ None
  }
}

/**
 * Implements a 'Two Phase Set' CRDT, also called a '2P-Set'.
 *
 * 2-phase sets consist of two G-Sets: one for adding and one for removing.
 * An element can only be added once and only removed once, and elements can
 * only be removed if they are present in the set. Removes naturally take
 * precedence over adds.
 *
 * A 2-phase set accumulate garbage of removed elements.
 */
case class TwoPhaseSet(
  private val adds: GSet = GSet.empty, private val removes: GSet = GSet.empty)
  extends ReplicatedData {

  type T = TwoPhaseSet

  /**
   * Scala API
   */
  def value: Set[Any] = adds.value -- removes.value

  /**
   * Java API
   */
  def getValue(): java.util.Set[Any] = {
    import scala.collection.JavaConverters._
    value.asJava
  }

  def contains(a: Any): Boolean = adds.contains(a) && !removes.contains(a)

  /**
   * Adds an element to the set.
   * The element cannot be added again after being removed.
   */
  def :+(element: Any): TwoPhaseSet = add(element)

  /**
   * Adds an element to the set.
   * The element cannot be added again after being removed.
   */
  def add(element: Any): TwoPhaseSet =
    if ((adds.value contains element) && (removes.value contains element))
      throw new IllegalStateException(s"Cannot add [$element] - already removed from set")
    else copy(adds = adds :+ element)

  /**
   * Removes an element from the set.
   * The element must be in the set.
   */
  def :-(element: Any): TwoPhaseSet = remove(element)

  /**
   * Removes an element from the set.
   * The element must be in the set.
   */
  def remove(element: Any): TwoPhaseSet =
    if (adds.value contains element) copy(removes = removes :+ element)
    else throw new IllegalStateException(s"Cannot remove [$element] - not in set")

  override def merge(that: TwoPhaseSet): TwoPhaseSet =
    copy(adds = that.adds.merge(this.adds), removes = that.removes.merge(this.removes))
}

