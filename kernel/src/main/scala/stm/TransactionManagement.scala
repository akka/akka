/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.stm

import java.util.concurrent.atomic.AtomicBoolean

import kernel.util.Logging

class TransactionAwareWrapperException(val cause: Throwable, val tx: Option[Transaction]) extends RuntimeException(cause) {
  override def toString(): String = "TransactionAwareWrapperException[" + cause + ", " + tx + "]"
}

object TransactionManagement {
  private val txEnabled = new AtomicBoolean(true)

  def isTransactionalityEnabled = txEnabled.get
  def enableTransactions = txEnabled.set(true)

  private[kernel] val threadBoundTx: ThreadLocal[Option[Transaction]] = {
    val tl = new ThreadLocal[Option[Transaction]]
    tl.set(None)
    tl
  }
}

// FIXME: STM that allows concurrent updates, detects collision, rolls back and restarts
trait TransactionManagement extends Logging {
  val id: String

  import TransactionManagement.threadBoundTx
  private[kernel] var activeTx: Option[Transaction] = None

  protected def startNewTransaction = {
    val newTx = new Transaction
    newTx.begin(id)
    val tx = Some(newTx)
    activeTx = tx
    threadBoundTx.set(tx)
  }

  protected def joinExistingTransaction = {
    val cflowTx = threadBoundTx.get
    if (!activeTx.isDefined && cflowTx.isDefined) {
      val currentTx = cflowTx.get
      currentTx.join(id)
      activeTx = Some(currentTx)
    }
  }

  protected def tryToPrecommitTransaction = if (activeTx.isDefined) activeTx.get.precommit(id)

  protected def tryToCommitTransaction: Boolean = if (activeTx.isDefined) {
    val tx = activeTx.get
    tx.commit(id)
    removeTransactionIfTopLevel
    true
  } else false

  protected def rollback(tx: Option[Transaction]) = tx match {
    case None => {} // no tx; nothing to do
    case Some(tx) =>
      tx.rollback(id)
  }

  protected def isInExistingTransaction = TransactionManagement.threadBoundTx.get.isDefined

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
}

