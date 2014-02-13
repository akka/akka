/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

object GSet {
  val empty: GSet = new GSet(Set.empty)
  def apply(): GSet = empty

  def unapply(value: Any): Option[Set[Any]] = value match {
    case s: GSet ⇒ Some(s.value)
    case _       ⇒ None
  }
}

/**
 * Implements a 'Add Set' CRDT, also called a 'G-Set'. You can't
 * remove an element of a G-Set.
 *
 * A G-Set doesn't accumulate any garbage apart from the elements themselves.
 */
case class GSet(elements: Set[Any]) extends ReplicatedData {

  type T = GSet

  /**
   * Scala API
   */
  def value: Set[Any] = elements

  /**
   * Java API
   */
  def getValue(): java.util.Set[Any] = {
    import scala.collection.JavaConverters._
    value.asJava
  }

  def contains(a: Any): Boolean = elements(a)

  /**
   * Adds an element to the set
   */
  def :+(element: Any): GSet = add(element)

  /**
   * Adds an element to the set
   */
  def add(element: Any): GSet = copy(elements + element)

  override def merge(that: GSet): GSet = copy(elements ++ that.elements)
}

