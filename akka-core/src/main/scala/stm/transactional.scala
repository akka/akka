/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

/**
 * For importing the transactional datastructures, including the primitive refs
 * and transactional data structures from Multiverse.
 */
package object transactional {
  type TransactionalMap[K,V] = se.scalablesolutions.akka.stm.TransactionalMap[K,V]
  val TransactionalMap =  se.scalablesolutions.akka.stm.TransactionalMap

  type TransactionalVector[T] = se.scalablesolutions.akka.stm.TransactionalVector[T]
  val TransactionalVector = se.scalablesolutions.akka.stm.TransactionalVector

  type BooleanRef = org.multiverse.transactional.refs.BooleanRef
  type ByteRef    = org.multiverse.transactional.refs.ByteRef
  type CharRef    = org.multiverse.transactional.refs.CharRef
  type DoubleRef  = org.multiverse.transactional.refs.DoubleRef
  type FloatRef   = org.multiverse.transactional.refs.FloatRef
  type IntRef     = org.multiverse.transactional.refs.IntRef
  type LongRef    = org.multiverse.transactional.refs.LongRef
  type ShortRef   = org.multiverse.transactional.refs.ShortRef

  type TransactionalReferenceArray[T] = org.multiverse.transactional.arrays.TransactionalReferenceArray[T]

  // These won't compile - something to do with vararg constructors? Check for Scala bug.

  // type TransactionalArrayList[T] = org.multiverse.transactional.collections.TransactionalArrayList[T]
  // type TransactionalLinkedList[T] = org.multiverse.transactional.collections.TransactionalLinkedList[T]

  type TransactionalThreadPoolExecutor = org.multiverse.transactional.executors.TransactionalThreadPoolExecutor
}
