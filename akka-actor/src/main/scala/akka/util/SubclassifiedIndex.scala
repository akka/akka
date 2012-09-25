/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

/**
 * Typeclass which describes a classification hierarchy. Observe the contract between `isEqual` and `isSubclass`!
 */
trait Subclassification[K] {
  /**
   * True if and only if x and y are of the same class.
   */
  def isEqual(x: K, y: K): Boolean
  /**
   * True if and only if x is a subclass of y; equal classes must be considered sub-classes!
   */
  def isSubclass(x: K, y: K): Boolean
}

object SubclassifiedIndex {

  class Nonroot[K, V](val key: K, _values: Set[V])(implicit sc: Subclassification[K]) extends SubclassifiedIndex[K, V](_values) {

    override def innerAddValue(key: K, value: V): Changes = {
      // break the recursion on super when key is found and transition to recursive add-to-set
      if (sc.isEqual(key, this.key)) addValue(value) else super.innerAddValue(key, value)
    }

    private def addValue(value: V): Changes = {
      val kids = subkeys flatMap (_ addValue value)
      if (!(values contains value)) {
        values += value
        kids :+ ((key, values))
      } else kids
    }

    override def innerRemoveValue(key: K, value: V): Changes = {
      // break the recursion on super when key is found and transition to recursive remove-from-set
      if (sc.isEqual(key, this.key)) removeValue(value) else super.innerRemoveValue(key, value)
    }

    override def removeValue(value: V): Changes = {
      val kids = subkeys flatMap (_ removeValue value)
      if (values contains value) {
        values -= value
        kids :+ ((key, Set(value)))
      } else kids
    }

    override def toString = subkeys.mkString("Nonroot(" + key + ", " + values + ",\n", ",\n", ")")
  }

  private[SubclassifiedIndex] def emptyMergeMap[K, V] = internalEmptyMergeMap.asInstanceOf[Map[K, Set[V]]]
  private[this] val internalEmptyMergeMap = Map[AnyRef, Set[AnyRef]]().withDefault(_ ⇒ Set[AnyRef]())
}

/**
 * Mutable index which respects sub-class relationships between keys:
 *
 *  - adding a key inherits from super-class
 *  - adding values applies to all sub-classes
 *  - removing values applies to all sub-classes
 *
 * Currently this class is only used to keep the tree and return changed key-
 * value-sets upon modification, since looking up the keys in an external
 * cache, e.g. HashMap, is faster than tree traversal which must use linear
 * scan at each level. Therefore, no value traversals are published.
 */
class SubclassifiedIndex[K, V] private (private var values: Set[V])(implicit sc: Subclassification[K]) {

  import SubclassifiedIndex._

  type Changes = Seq[(K, Set[V])]

  protected var subkeys = Vector.empty[Nonroot[K, V]]

  def this()(implicit sc: Subclassification[K]) = this(Set.empty)

  /**
   * Add key to this index which inherits its value set from the most specific
   * super-class which is known.
   */
  def addKey(key: K): Changes = mergeChangesByKey(innerAddKey(key))

  protected def innerAddKey(key: K): Changes = {
    var found = false
    val ch = subkeys flatMap { n ⇒
      if (sc.isEqual(key, n.key)) {
        found = true
        Nil
      } else if (sc.isSubclass(key, n.key)) {
        found = true
        n.innerAddKey(key)
      } else Nil
    }
    if (!found) {
      integrate(new Nonroot(key, values))
      Seq((key, values))
    } else ch
  }

  /**
   * Add value to all keys which are subclasses of the given key. If the key
   * is not known yet, it is inserted as if using addKey.
   */
  def addValue(key: K, value: V): Changes = mergeChangesByKey(innerAddValue(key, value))

  protected def innerAddValue(key: K, value: V): Changes = {
    var found = false
    val ch = subkeys flatMap { n ⇒
      if (sc.isSubclass(key, n.key)) {
        found = true
        n.innerAddValue(key, value)
      } else Nil
    }
    if (!found) {
      val v = values + value
      val n = new Nonroot(key, v)
      integrate(n)
      n.innerAddValue(key, value) :+ (key -> v)
    } else ch
  }

  /**
   * Remove value from all keys which are subclasses of the given key.
   * @return The keys and values that have been removed.
   */
  def removeValue(key: K, value: V): Changes = mergeChangesByKey(innerRemoveValue(key, value))
  protected def innerRemoveValue(key: K, value: V): Changes = {
    var found = false
    val ch = subkeys flatMap { n ⇒
      if (sc.isSubclass(key, n.key)) {
        found = true
        n.innerRemoveValue(key, value)
      } else Nil
    }
    if (!found) {
      val n = new Nonroot(key, values)
      integrate(n)
      n.removeValue(value)
    } else ch
  }

  /**
   * Remove value from all keys in the index.
   */
  def removeValue(value: V): Changes = mergeChangesByKey(subkeys flatMap (_ removeValue value))

  override def toString = subkeys.mkString("SubclassifiedIndex(" + values + ",\n", ",\n", ")")

  /**
   * Add new Nonroot below this node and check all existing nodes for subclass relationship.
   *
   * @return true if and only if the new node has received subkeys during this operation.
   */
  private def integrate(n: Nonroot[K, V]) {
    val (subsub, sub) = subkeys partition (k ⇒ sc.isSubclass(k.key, n.key))
    if (sub.size == subkeys.size) {
      subkeys :+= n
    } else {
      n.subkeys = subsub
      subkeys = sub :+ n
    }
  }

  private def mergeChangesByKey(changes: Changes): Changes =
    (emptyMergeMap[K, V] /: changes) {
      case (m, (k, s)) ⇒ m.updated(k, m(k) ++ s)
    }.toSeq
}
