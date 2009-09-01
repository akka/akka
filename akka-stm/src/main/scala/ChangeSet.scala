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

  // FIXME: add hashCode and equals - VERY IMPORTANT
}

