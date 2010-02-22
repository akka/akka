/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicBoolean

import se.scalablesolutions.akka.util.Logging

import org.multiverse.api.ThreadLocalTransaction._

class StmException(msg: String) extends RuntimeException(msg)

class TransactionAwareWrapperException(
    val cause: Throwable, val tx: Option[Transaction]) extends RuntimeException(cause) {
  override def toString(): String = "TransactionAwareWrapperException[" + cause + ", " + tx + "]"
}

object TransactionManagement extends TransactionManagement {
  import se.scalablesolutions.akka.Config._

  val MAX_NR_OF_RETRIES = config.getInt("akka.stm.max-nr-of-retries", 100)
  val TRANSACTION_ENABLED = new AtomicBoolean(config.getBool("akka.stm.service", false))

  def isTransactionalityEnabled = TRANSACTION_ENABLED.get
  def disableTransactions = TRANSACTION_ENABLED.set(false)

  private[akka] val currentTransaction = new ThreadLocal[Option[Transaction]]() {
    override protected def initialValue: Option[Transaction] = None
  }
}

trait TransactionManagement extends Logging {
  import TransactionManagement.currentTransaction

  private[akka] def createNewTransaction = currentTransaction.set(Some(new Transaction))

  private[akka] def setTransaction(transaction: Option[Transaction]) = if (transaction.isDefined) {
    val tx = transaction.get
    currentTransaction.set(transaction)
    if (tx.transaction.isDefined) setThreadLocalTransaction(tx.transaction.get)
    else throw new IllegalStateException("No transaction defined")
  }

  private[akka] def clearTransaction = {
    currentTransaction.set(None)
    setThreadLocalTransaction(null)
  }

  private[akka] def getTransactionInScope = currentTransaction.get.get
  
  private[akka] def isTransactionInScope = currentTransaction.get.isDefined

  private[akka] def isTransactionTopLevel = if (isTransactionInScope) getTransactionInScope.isTopLevel

  private[akka] def incrementTransaction = if (isTransactionInScope) getTransactionInScope.increment

  private[akka] def decrementTransaction = if (isTransactionInScope) getTransactionInScope.decrement
}

