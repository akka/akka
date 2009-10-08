/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicBoolean

import se.scalablesolutions.akka.dispatch.MessageInvocation
import se.scalablesolutions.akka.util.Logging

import org.codehaus.aspectwerkz.proxy.Uuid

import scala.collection.mutable.HashSet

// FIXME is java.util.UUID better?

import org.multiverse.utils.TransactionThreadLocal._

class TransactionRollbackException(msg: String) extends RuntimeException(msg)

class TransactionAwareWrapperException(val cause: Throwable, val tx: Option[Transaction]) extends RuntimeException(cause) {
  override def toString(): String = "TransactionAwareWrapperException[" + cause + ", " + tx + "]"
}

object TransactionManagement {
  import se.scalablesolutions.akka.Config._
  val TIME_WAITING_FOR_COMPLETION = config.getInt("akka.stm.wait-for-completion", 1000)
  val NR_OF_TIMES_WAITING_FOR_COMPLETION = config.getInt("akka.stm.wait-nr-of-times", 3)
  val MAX_NR_OF_RETRIES = config.getInt("akka.stm.max-nr-of-retries", 10)
  val TRANSACTION_ENABLED = new AtomicBoolean(config.getBool("akka.stm.service", false))
  // FIXME reenable 'akka.stm.restart-on-collision' when new STM is in place 
  val RESTART_TRANSACTION_ON_COLLISION = false //akka.Kernel.config.getBool("akka.stm.restart-on-collision", true)

  def isTransactionalityEnabled = TRANSACTION_ENABLED.get
  def disableTransactions = TRANSACTION_ENABLED.set(false)

  private[akka] val currentTransaction: ThreadLocal[Option[Transaction]] = new ThreadLocal[Option[Transaction]]() {
    override protected def initialValue: Option[Transaction] = None
  }
}

trait TransactionManagement extends Logging {
  var uuid = Uuid.newUuid.toString
  
  import TransactionManagement.currentTransaction
  private[akka] val activeTransactions = new HashSet[Transaction]

  protected def startNewTransaction(message: MessageInvocation) = {
    val newTx = new Transaction
    newTx.begin(uuid, message)
    activeTransactions += newTx
    currentTransaction.set(Some(newTx))
    setThreadLocalTransaction(newTx.transaction)
  }

  protected def joinExistingTransaction = {
    val cflowTx = currentTransaction.get
    if (activeTransactions.isEmpty && cflowTx.isDefined) {
      val currentTx = cflowTx.get
      currentTx.join(uuid)
      activeTransactions += currentTx
    }
  }                                 

  protected def tryToPrecommitTransactions = activeTransactions.foreach(_.precommit(uuid))

  protected def tryToCommitTransactions = {
    for (tx <- activeTransactions) {
      if (tx.commit(uuid)) activeTransactions -= tx
      else if (tx.isTopLevel) {
        println("------------ COULD NOT COMMIT -- WAITING OR TIMEOUT? ---------")
        //tx.retry
      } else {
        println("---------- tryToCommitTransactions ")
          println("---------- tryToCommitTransactions tx.isTopLevel " + tx.isTopLevel)
          println("---------- tryToCommitTransactions tx.depth.get " + tx.depth.get)
          println("---------- tryToCommitTransactions tx.status_? " + tx.status_?)
          rollback(Some(tx))
        // continue, try to commit on next received message
        // FIXME check if TX hase timed out => throw exception
      }
    }
  }
  
  protected def rollback(tx: Option[Transaction]) = tx match {
    case None => {} // no tx; nothing to do
    case Some(tx) =>
      tx.rollback(uuid)
      activeTransactions -= tx
  }

  protected def setTransaction(transaction: Option[Transaction]) = if (transaction.isDefined) {
    currentTransaction.set(transaction)
    setThreadLocalTransaction(transaction.get.transaction)
  }

  protected def clearTransaction = {
    currentTransaction.set(None)
    setThreadLocalTransaction(null)
  }

  protected def isInExistingTransaction = currentTransaction.get.isDefined

  protected def incrementTransaction = if (currentTransaction.get.isDefined) currentTransaction.get.get.increment

  protected def decrementTransaction = if (currentTransaction.get.isDefined) currentTransaction.get.get.decrement

  protected def removeTransactionIfTopLevel(tx: Transaction) = if (tx.isTopLevel) { activeTransactions -= tx }
}

