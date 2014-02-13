/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import java.util.UUID

// FIXME remove this, replaced with the more efficient ORSet

object ORSetAsInPaper {
  val empty: ORSetAsInPaper = new ORSetAsInPaper
  def apply(): ORSetAsInPaper = empty

  def unapply(value: Any): Option[Set[Any]] = value match {
    case s: ORSetAsInPaper ⇒ Some(s.value)
    case _                 ⇒ None
  }
}

/**
 * Implements a CRDT 'Observed Remove Set' also called a 'OR-Set'.
 *
 * This will be removed, replaced by the more efficient [[ORSet]]
 *
 * A OR-Set accumulate garbage for removed elements.
 */
case class ORSetAsInPaper(
  private[akka] val elements: Map[Any, Set[UUID]] = Map.empty,
  private[akka] val tombstones: Map[Any, Set[UUID]] = Map.empty)
  extends ReplicatedData {

  type T = ORSetAsInPaper

  /**
   * Scala API
   */
  def value: Set[Any] = elements.keySet

  /**
   * Java API
   */
  def getValue(): java.util.Set[Any] = {
    import scala.collection.JavaConverters._
    value.asJava
  }

  /**
   * Adds an element to the set
   */
  def :+(element: Any): ORSetAsInPaper = add(element)

  /**
   * Adds an element to the set
   */
  def add(element: Any): ORSetAsInPaper =
    copy(elements = elements.updated(element, elements.getOrElse(element, Set.empty) + UUID.randomUUID()))

  /**
   * Removes an element from the set.
   */
  def :-(element: Any): ORSetAsInPaper = remove(element)

  /**
   * Removes an element from the set.
   */
  def remove(element: Any): ORSetAsInPaper = {
    val observedUuids = elements.getOrElse(element, Set.empty)
    copy(elements = elements - element,
      tombstones = tombstones.updated(element, tombstones.getOrElse(element, Set.empty) ++ observedUuids))
  }

  override def merge(that: ORSetAsInPaper): ORSetAsInPaper = {
    var mergedTombstones = that.tombstones
    for ((element, thisUuids) ← this.tombstones) {
      mergedTombstones = mergedTombstones.updated(element, thisUuids ++ mergedTombstones.getOrElse(element, Iterator.empty))
    }

    var mergedElements = that.elements
    for ((element, thisUuids) ← this.elements) {
      mergedElements = mergedElements.updated(element, thisUuids ++ mergedElements.getOrElse(element, Iterator.empty))
    }

    for ((element, tombstonesUuids) ← mergedTombstones) {
      mergedElements.get(element) match {
        case Some(elementsUuids) ⇒
          val remainingUuids = (elementsUuids -- tombstonesUuids)
          if (remainingUuids.isEmpty)
            mergedElements -= element
          else
            mergedElements = mergedElements.updated(element, remainingUuids)
        case None ⇒
      }
    }

    copy(elements = mergedElements, tombstones = mergedTombstones)
  }
}

