package akka.stm

/**
 * TransactionalSet : completely based on TransactionalMap
 * @author - Dhananjay Nene
 */

/*
 * TODO: Change package names in imports. This has been compiled against akka_2.8.0-1.0-M1.zip 
 */

import scala.collection.mutable.HashSet
import se.scalablesolutions.akka.stm.{Transactional, Ref}
import se.scalablesolutions.akka.actor.{newUuid}

/**
 * Transactional set that implements the mutable Set interface with an underlying Ref and HashSet.
 */

object TransactionalSet {
  def apply[K]() = new TransactionalSet[K]()

  def apply[K](elems: K*) = new TransactionalSet(HashSet(elems: _*))
}

/**
 * Transactional Set that implements the mutable Set interface with an underlying Ref and HashSet.
 *
 * From Scala you can use TSet as a shorter alias for TransactionalSet.
 */

class TransactionalSet[T](initialValue: HashSet[T]) extends Transactional with scala.collection.mutable.Set[T] {
  def this() = this(HashSet[T]())

  val uuid = newUuid.toString

  private[this] val ref = Ref(initialValue)

  override def -=(elem: T) = {
    ref.set(ref.get - elem)
    this
  }

  override def +=(elem: T) = {
    ref.set(ref.get + elem)
    this
  }

  override def += (elem1: T, elem2: T, elems: T*) = {
	  ref.set(ref.get + (elem1, elem2, elems: _*))
	  this
  }

  override def -= (elem1: T, elem2: T, elems: T*) = {
	  ref.set(ref.get - (elem1, elem2, elems: _*))
	  this
  }

  override def ++= (xs: TraversableOnce[T]) = { 
	ref.set(ref.get ++ xs) 
	this 
  }
  
  override def --= (xs: TraversableOnce[T]) = { 
	ref.set(ref.get -- xs) 
	this 
  }
  
  def iterator = ref.get.iterator

  override def elements: Iterator[T] = ref.get.iterator

  override def contains(elem: T): Boolean = ref.get.contains(elem)

  override def clear = ref.swap(HashSet[T]())

  override def size: Int = ref.get.size

  override def hashCode: Int = System.identityHashCode(this);

  override def equals(other: Any): Boolean =
    other.isInstanceOf[TransactionalSet[_]] &&
    other.hashCode == hashCode

//  TODO: Not sure, but I wasn't able to refer to Stm.activeTransaction when compiling
//  override def toString = if (Stm.activeTransaction) super.toString else "<TransactionalSet>"
  override def toString = "<TransactionalSet>"
}
