/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.util.Logging

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

import org.multiverse.api.{StmUtils => MultiverseStmUtils}
import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.commitbarriers.CountDownCommitBarrier
import org.multiverse.templates.{TransactionalCallable, OrElseTemplate}

class TransactionSetAbortedException(msg: String) extends RuntimeException(msg)

// TODO Should we remove TransactionAwareWrapperException? Not used anywhere yet.
class TransactionAwareWrapperException(val cause: Throwable, val tx: Option[Transaction]) extends RuntimeException(cause) {
  override def toString = "TransactionAwareWrapperException[" + cause + ", " + tx + "]"
}

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

/**
 * Local transaction management, local in the context of threads.
 * Use this if you do <b>not</b> need to have one transaction span
 * multiple threads (or Actors).
 * <p/>
 * Example of atomic transaction management using the atomic block.
 * <p/>
 * <pre>
 * import se.scalablesolutions.akka.stm.local._
 *
 * atomic  {
 *   // do something within a transaction
 * }
 * </pre>
 */
class LocalStm extends TransactionManagement with Logging {

  val DefaultLocalTransactionConfig = TransactionConfig()
  val DefaultLocalTransactionFactory = TransactionFactory(DefaultLocalTransactionConfig, "DefaultLocalTransaction")

  def atomic[T](body: => T)(implicit factory: TransactionFactory = DefaultLocalTransactionFactory): T = atomic(factory)(body)

  def atomic[T](factory: TransactionFactory)(body: => T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = {
        factory.addHooks
        val result = body
        log.ifTrace("Committing local transaction [" + mtx + "]")
        result
      }
    })
  }
}

/**
 * Global transaction management, global in the context of multiple threads.
 * Use this if you need to have one transaction span multiple threads (or Actors).
 * <p/>
 * Example of atomic transaction management using the atomic block:
 * <p/>
 * <pre>
 * import se.scalablesolutions.akka.stm.global._
 *
 * atomic  {
 *   // do something within a transaction
 * }
 * </pre>
 */
class GlobalStm extends TransactionManagement with Logging {

  val DefaultGlobalTransactionConfig = TransactionConfig()
  val DefaultGlobalTransactionFactory = TransactionFactory(DefaultGlobalTransactionConfig, "DefaultGlobalTransaction")

  def atomic[T](body: => T)(implicit factory: TransactionFactory = DefaultGlobalTransactionFactory): T = atomic(factory)(body)

  def atomic[T](factory: TransactionFactory)(body: => T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = {
        if (!isTransactionSetInScope) createNewTransactionSet
        factory.addHooks
        val result = body
        val txSet = getTransactionSetInScope
        log.ifTrace("Committing global transaction [" + mtx + "]\n\tand joining transaction set [" + txSet + "]")
        try {
          txSet.tryJoinCommit(
            mtx,
            TransactionConfig.DefaultTimeout.length,
            TransactionConfig.DefaultTimeout.unit) 
        // Need to catch IllegalStateException until we have fix in Multiverse, since it throws it by mistake
        } catch { case e: IllegalStateException => {} }
        result
      }
    })
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
