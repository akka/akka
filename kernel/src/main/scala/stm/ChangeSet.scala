/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.stm

import kernel.state.{Transactional, TransactionalMap}
import kernel.util.Helpers.ReadWriteLock
import scala.collection.immutable.HashSet

@serializable
class ChangeSet {
  private val lock = new ReadWriteLock

  private var transactionalItems: Set[Transactional] = new HashSet
  private[kernel] def +(item: Transactional) = lock.withWriteLock {
    transactionalItems += item
  }
  private[kernel] def items: List[Transactional] = lock.withReadLock {
    transactionalItems.toList.asInstanceOf[List[Transactional]]
  }

  private[kernel] def clear = lock.withWriteLock {
    transactionalItems = new HashSet 
  }


  /*
  // TX Maps
  private[kernel] var _maps: List[TransactionalMap[_, _]] = Nil
  private[kernel] def maps_=(maps: List[TransactionalMap[_, _]]) = lock.withWriteLock {
    _maps = maps
  }
  private[kernel] def maps: List[TransactionalMap[_, _]] = lock.withReadLock {
    _maps
  }

  // TX Vectors
  private[kernel] var _vectors: List[TransactionalVector[_]] = Nil
  private[kernel] def vectors_=(vectors: List[TransactionalVector[_]]) = lock.withWriteLock {
    _vectors = vectors
  }
  private[kernel] def vectors: List[TransactionalVector[_]] = lock.withReadLock {
    _vectors
  }

  // TX Refs
  private[kernel] var _refs: List[TransactionalRef[_]] = Nil
  private[kernel] def refs_=(refs: List[TransactionalRef[_]]) = lock.withWriteLock {
    _refs = refs
  }
  private[kernel] def refs: List[TransactionalRef[_]] = lock.withReadLock {
    _refs
  }
  */
}

