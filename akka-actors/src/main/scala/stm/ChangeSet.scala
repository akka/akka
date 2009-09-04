/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.stm

import state.{Transactional, TransactionalMap}
import util.Helpers.ReadWriteLock
import scala.collection.immutable.HashSet

@serializable
class ChangeSet {
  private val lock = new ReadWriteLock

  private var transactionalItems: Set[Transactional] = new HashSet
  private[akka] def +(item: Transactional) = lock.withWriteLock {
    transactionalItems += item
  }
  private[akka] def items: List[Transactional] = lock.withReadLock {
    transactionalItems.toList.asInstanceOf[List[Transactional]]
  }

  private[akka] def clear = lock.withWriteLock {
    transactionalItems = new HashSet 
  }

  // FIXME: add hashCode and equals - VERY IMPORTANT
}

