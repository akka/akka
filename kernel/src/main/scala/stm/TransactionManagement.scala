/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.stm

import java.util.concurrent.atomic.AtomicBoolean

import kernel.util.Logging
import org.codehaus.aspectwerkz.proxy.Uuid

class TransactionAwareWrapperException(val cause: Throwable, val tx: Option[Transaction]) extends RuntimeException(cause) {
  override def toString(): String = "TransactionAwareWrapperException[" + cause + ", " + tx + "]"
}

object TransactionManagement {
  val TIME_WAITING_FOR_COMPLETION = kernel.Kernel.config.getInt("akka.stm.wait-for-completion", 100)
  val NR_OF_TIMES_WAITING_FOR_COMPLETION = kernel.Kernel.config.getInt("akka.stm.wait-nr-of-times", 3)
  val TRANSACTION_ENABLED = new AtomicBoolean(kernel.Kernel.config.getBool("akka.stm.service", true))
  val RESTART_TRANSACTION_ON_COLLISION = kernel.Kernel.config.getBool("akka.stm.restart-transaction", true)

  def isTransactionalityEnabled = TRANSACTION_ENABLED.get
  def disableTransactions = TRANSACTION_ENABLED.set(false)

  private[kernel] val threadBoundTx: ThreadLocal[Option[Transaction]] = new ThreadLocal[Option[Transaction]]() {
    override protected def initialValue: Option[Transaction] = None
  }
}

// FIXME: STM that allows concurrent updates, detects collision, rolls back and restarts
trait TransactionManagement extends Logging {
  val uuid = Uuid.newUuid.toString

  import TransactionManagement.threadBoundTx
  private[kernel] var activeTx: Option[Transaction] = None

  protected def startNewTransaction: Option[Transaction] = {
    val newTx = new Transaction
    newTx.begin(uuid)
    val tx = Some(newTx)
    activeTx = tx
    threadBoundTx.set(tx)
    tx
  }

  protected def joinExistingTransaction = {
    val cflowTx = threadBoundTx.get
    if (!activeTx.isDefined && cflowTx.isDefined) {
      val currentTx = cflowTx.get
      currentTx.join(uuid)
      activeTx = Some(currentTx)
    }
  }

  protected def tryToPrecommitTransaction = if (activeTx.isDefined) activeTx.get.precommit(uuid)

  protected def tryToCommitTransaction: Boolean = if (activeTx.isDefined) {
    val tx = activeTx.get
    if (tx.commit(uuid)) {
      removeTransactionIfTopLevel
      true
    } else false
  } else true

  protected def rollback(tx: Option[Transaction]) = tx match {
    case None => {} // no tx; nothing to do
    case Some(tx) =>
      tx.rollback(uuid)
  }

  protected def rollbackForRescheduling(tx: Option[Transaction]) = tx match {
    case None => {} // no tx; nothing to do
    case Some(tx) =>
      tx.rollbackForRescheduling(uuid)
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

