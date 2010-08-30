/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.AkkaException

import org.multiverse.api.{StmUtils => MultiverseStmUtils}
import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.commitbarriers.CountDownCommitBarrier
import org.multiverse.templates.OrElseTemplate

class TransactionSetAbortedException(msg: String) extends AkkaException(msg)

/**
 * Internal helper methods and properties for transaction management.
 */
object TransactionManagement extends TransactionManagement {
  import se.scalablesolutions.akka.config.Config._

  // FIXME move to stm.global.fair?
  val FAIR_TRANSACTIONS = config.getBool("akka.stm.fair", true)

  private[akka] val transactionSet = new ThreadLocal[Option[CountDownCommitBarrier]]() {
    override protected def initialValue: Option[CountDownCommitBarrier] = None
  }

  private[akka] val transaction = new ThreadLocal[Option[Transaction]]() {
    override protected def initialValue: Option[Transaction] = None
  }

  private[akka] def getTransactionSet: CountDownCommitBarrier = {
    val option = transactionSet.get
    if ((option eq null) || option.isEmpty) throw new StmConfigurationException("No Transaction set in scope")
    else option.get
  }

  private[akka] def getTransaction: Transaction = {
    val option = transaction.get
    if ((option eq null) || option.isEmpty) throw new StmConfigurationException("No Transaction in scope")
    option.get
  }
}

/**
 * Internal helper methods for transaction management.
 */
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

trait StmUtil {
  /**
   * Schedule a deferred task on the thread local transaction (use within an atomic).
   * This is executed when the transaction commits.
   */
  def deferred[T](body: => T): Unit =
    MultiverseStmUtils.scheduleDeferredTask(new Runnable { def run = body })

  /**
   * Schedule a compensating task on the thread local transaction (use within an atomic).
   * This is executed when the transaction aborts.
   */
  def compensating[T](body: => T): Unit =
    MultiverseStmUtils.scheduleCompensatingTask(new Runnable { def run = body })

  /**
   * STM retry for blocking transactions (use within an atomic).
   * Can be used to wait for a condition.
   */
  def retry = MultiverseStmUtils.retry

  /**
   * Use either-orElse to combine two blocking transactions.
   * Usage:
   * <pre>
   * either {
   *   ...
   * } orElse {
   *   ...
   * }
   * </pre>
   */
  def either[T](firstBody: => T) = new {
    def orElse(secondBody: => T) = new OrElseTemplate[T] {
      def either(mtx: MultiverseTransaction) = firstBody
      def orelse(mtx: MultiverseTransaction) = secondBody
    }.execute()
  }
}

trait StmCommon {
  type TransactionConfig = se.scalablesolutions.akka.stm.TransactionConfig
  val TransactionConfig = se.scalablesolutions.akka.stm.TransactionConfig

  type TransactionFactory = se.scalablesolutions.akka.stm.TransactionFactory
  val TransactionFactory = se.scalablesolutions.akka.stm.TransactionFactory

  val Propagation = se.scalablesolutions.akka.stm.Transaction.Propagation

  val TraceLevel = se.scalablesolutions.akka.stm.Transaction.TraceLevel

  type Ref[T] = se.scalablesolutions.akka.stm.Ref[T]
  val Ref = se.scalablesolutions.akka.stm.Ref
}
