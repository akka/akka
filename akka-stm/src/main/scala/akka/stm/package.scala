/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka

/**
 * For easily importing everything needed for STM.
 */
package object stm extends akka.stm.Stm with akka.stm.StmUtil {

  // Shorter aliases for transactional map and vector

  type TMap[K, V] = akka.stm.TransactionalMap[K, V]
  val TMap = akka.stm.TransactionalMap

  type TVector[T] = akka.stm.TransactionalVector[T]
  val TVector = akka.stm.TransactionalVector

  // Multiverse primitive refs

  type BooleanRef = org.multiverse.transactional.refs.BooleanRef
  type ByteRef = org.multiverse.transactional.refs.ByteRef
  type CharRef = org.multiverse.transactional.refs.CharRef
  type DoubleRef = org.multiverse.transactional.refs.DoubleRef
  type FloatRef = org.multiverse.transactional.refs.FloatRef
  type IntRef = org.multiverse.transactional.refs.IntRef
  type LongRef = org.multiverse.transactional.refs.LongRef
  type ShortRef = org.multiverse.transactional.refs.ShortRef

  // Multiverse transactional datastructures

  type TransactionalReferenceArray[T] = org.multiverse.transactional.arrays.TransactionalReferenceArray[T]
  type TransactionalThreadPoolExecutor = org.multiverse.transactional.executors.TransactionalThreadPoolExecutor

  // These won't compile:
  // Transaction arg is added after varargs with byte code rewriting but Scala compiler doesn't allow this

  // type TransactionalArrayList[T] = org.multiverse.transactional.collections.TransactionalArrayList[T]
  // type TransactionalLinkedList[T] = org.multiverse.transactional.collections.TransactionalLinkedList[T]
}
