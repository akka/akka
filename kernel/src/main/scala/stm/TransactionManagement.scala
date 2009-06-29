/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.stm

import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

import kernel.state.{TransactionalMap, TransactionalRef, TransactionalVector}
import kernel.util.Logging

class TransactionAwareWrapperException(val cause: Throwable, val tx: Option[Transaction]) extends RuntimeException(cause) {
  override def toString(): String = "TransactionAwareWrapperException[" + cause + ", " + tx + "]"
}

object TransactionManagement {
  private val txEnabled = new AtomicBoolean(true)

  def isTransactionsEnabled = txEnabled.get
  def enableTransactions = txEnabled.set(true)

  private[kernel] val threadBoundTx: ThreadLocal[Option[Transaction]] = {
    val tl = new ThreadLocal[Option[Transaction]]
    tl.set(None)
    tl
  }
}

// FIXME: STM that allows concurrent updates, detects collision, rolls back and restarts
trait TransactionManagement extends Logging {
  val transactionalInstance: AnyRef

  private lazy val changeSet = new ChangeSet(transactionalInstance.getClass.getName)

  import TransactionManagement.threadBoundTx
  private[kernel] var activeTx: Option[Transaction] = None

  protected def startNewTransaction = {
    storeTransactionalItemsFor(transactionalInstance)
    val newTx = new Transaction
    newTx.begin(changeSet)
    val tx = Some(newTx)
    activeTx = tx
    threadBoundTx.set(tx)
  }

  protected def joinExistingTransaction = {
    val cflowTx = threadBoundTx.get
    if (activeTx.isDefined && cflowTx.isDefined && activeTx.get.id == cflowTx.get.id) {
      storeTransactionalItemsFor(transactionalInstance)
      val currentTx = cflowTx.get
      currentTx.join(changeSet)
      activeTx = Some(currentTx)
    }
  }

  protected def tryToPrecommitTransaction = if (activeTx.isDefined) activeTx.get.precommit(changeSet)

  protected def tryToCommitTransaction: Boolean = if (activeTx.isDefined) {
    val tx = activeTx.get
    tx.commit(changeSet)
    removeTransactionIfTopLevel
    true
  } else false

  protected def rollback(tx: Option[Transaction]) = tx match {
    case None => {} // no tx; nothing to do
    case Some(tx) =>
      tx.rollback(changeSet)
  }

  protected def isInExistingTransaction = {
    println(TransactionManagement)
    println(TransactionManagement.threadBoundTx)
    println(TransactionManagement.threadBoundTx.get)
    println(TransactionManagement.threadBoundTx.get.isDefined)
    TransactionManagement.threadBoundTx.get.isDefined
  }

  protected def isTransactionAborted = activeTx.isDefined && activeTx.get.isAborted

  protected def incrementTransaction =  if (activeTx.isDefined) activeTx.get.increment

  protected def decrementTransaction =  if (activeTx.isDefined) activeTx.get.decrement

  protected def removeTransactionIfTopLevel =
    if (activeTx.isDefined && activeTx.get.topLevel_?) {
      activeTx = None
      threadBoundTx.set(None)
    }

  protected def reenteringExistingTransaction= if (activeTx.isDefined) {
    val cflowTx = threadBoundTx.get
    if (cflowTx.isDefined && cflowTx.get.id == activeTx.get.id) false
    else true
  } else true

  /**
   * Search for transactional items for a specific target instance, crawl the class hierarchy recursively up to the top.
   */
  protected def storeTransactionalItemsFor(targetInstance: AnyRef) = {
    require(targetInstance != null)
    var maps:    List[TransactionalMap[_, _]] = Nil
    var refs:    List[TransactionalRef[_]] = Nil
    var vectors: List[TransactionalVector[_]] = Nil

    def getTransactionalItemsFor(target: Class[_]):
      Tuple3[List[TransactionalMap[_, _]], List[TransactionalVector[_]], List[TransactionalRef[_]]] = {
    for {
        field <- target.getDeclaredFields.toArray.toList.asInstanceOf[List[Field]]
        fieldType = field.getType
        if (fieldType == classOf[TransactionalMap[_, _]]) ||
           (fieldType == classOf[TransactionalVector[_]]) ||
           (fieldType == classOf[TransactionalRef[_]])
        txItem = {
          field.setAccessible(true)
          field.get(targetInstance)
        }
        if txItem != null
      } {
        log.debug("Managing transactional state [%s]", field)
        if (txItem.isInstanceOf[TransactionalMap[_, _]])      maps    ::= txItem.asInstanceOf[TransactionalMap[_, _]]
        else if (txItem.isInstanceOf[TransactionalRef[_]])    refs    ::= txItem.asInstanceOf[TransactionalRef[_]]
        else if (txItem.isInstanceOf[TransactionalVector[_]]) vectors ::= txItem.asInstanceOf[TransactionalVector[_]]
      }
      val parent = target.getSuperclass
      if (parent == classOf[Object]) (maps, vectors, refs)
      else getTransactionalItemsFor(parent)
    }

    // start the search for transactional items, crawl the class hierarchy up until we reach Object
    val (m, v, r) = getTransactionalItemsFor(targetInstance.getClass)
    changeSet.maps = m
    changeSet.vectors = v
    changeSet.refs = r
  }

  /*
  protected def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      throw new TransactionAwareWrapperException(cause, activeTx)
    } else {
      if (future.result.isDefined) {
        val (res, tx) = future.result.get.asInstanceOf[Tuple2[AnyRef, Option[Transaction]]]
        Some(res).asInstanceOf[Option[T]]
      } else None
    }
      */
}

