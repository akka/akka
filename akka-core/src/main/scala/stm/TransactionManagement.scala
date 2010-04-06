/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.util.Logging

import java.util.concurrent.atomic.AtomicBoolean

import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.commitbarriers.CountDownCommitBarrier

class StmException(msg: String) extends RuntimeException(msg)

class TransactionAwareWrapperException(val cause: Throwable, val tx: Option[Transaction]) extends RuntimeException(cause) {
  override def toString = "TransactionAwareWrapperException[" + cause + ", " + tx + "]"
}

object TransactionManagement extends TransactionManagement {
  import se.scalablesolutions.akka.config.Config._

  val TRANSACTION_ENABLED =      new AtomicBoolean(config.getBool("akka.stm.service", false))
  val FAIR_TRANSACTIONS =        config.getBool("akka.stm.fair", true)
  val INTERRUPTIBLE =            config.getBool("akka.stm.interruptible", true)
  val MAX_NR_OF_RETRIES =        config.getInt("akka.stm.max-nr-of-retries", 1000)
  val TRANSACTION_TIMEOUT =      config.getInt("akka.stm.timeout", 10000)
  val SMART_TX_LENGTH_SELECTOR = config.getBool("akka.stm.smart-tx-length-selector", true)
  def isTransactionalityEnabled = TRANSACTION_ENABLED.get

  def disableTransactions = TRANSACTION_ENABLED.set(false)

  private[akka] val transactionSet = new ThreadLocal[Option[CountDownCommitBarrier]]() {
    override protected def initialValue: Option[CountDownCommitBarrier] = None
  }

  private[akka] val transaction = new ThreadLocal[Option[Transaction]]() {
    override protected def initialValue: Option[Transaction] = None
  }

  private[akka] def getTransactionSet: CountDownCommitBarrier = {
    val option = transactionSet.get
    if ((option eq null) || option.isEmpty) throw new IllegalStateException("No Transaction set in scope")
    else option.get
  }

  private[akka] def getTransaction: Transaction = {
    val option = transaction.get
    if ((option eq null) || option.isEmpty) throw new IllegalStateException("No Transaction in scope")
    option.get
  }
}

trait TransactionManagement {

  private[akka] def createNewTransactionSet: CountDownCommitBarrier = {
    val txSet = new CountDownCommitBarrier(1, TransactionManagement.FAIR_TRANSACTIONS)
    TransactionManagement.transactionSet.set(Some(txSet))
    txSet
  }

  private[akka] def setTransactionSet(txSet: Option[CountDownCommitBarrier]) =
    if (txSet.isDefined) TransactionManagement.transactionSet.set(txSet)

  private[akka] def setTransaction(tx: Option[Transaction]) =
    if (tx.isDefined) TransactionManagement.transaction.set(tx)

  private[akka] def clearTransactionSet = {
    TransactionManagement.transactionSet.set(None)
  }

  private[akka] def clearTransaction = {
    TransactionManagement.transaction.set(None)
    setThreadLocalTransaction(null)
  }

  private[akka] def getTransactionSetInScope = TransactionManagement.getTransactionSet

  private[akka] def getTransactionInScope = TransactionManagement.getTransaction

  private[akka] def isTransactionSetInScope = {
    val option = TransactionManagement.transactionSet.get
    (option ne null) && option.isDefined
  }

  private[akka] def isTransactionInScope = {
    val option = TransactionManagement.transaction.get
    (option ne null) && option.isDefined
  }
}
