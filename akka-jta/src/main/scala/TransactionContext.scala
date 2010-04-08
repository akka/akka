/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.jta

import javax.transaction.{Transaction, Status, TransactionManager}

import se.scalablesolutions.akka.util.Logging

/**
 * JTA Transaction service.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait TransactionService {
  def transactionManager: TransactionManager  
}

/**
 * Base monad for the transaction monad implementations.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait TransactionMonad {

  // -----------------------------
  // Monadic definitions
  // -----------------------------

  def map[T](f: TransactionMonad => T): T
  def flatMap[T](f: TransactionMonad => T): T
  def foreach(f: TransactionMonad => Unit): Unit
  def filter(f: TransactionMonad => Boolean): TransactionMonad =
    if (f(this)) this else TransactionContext.NoOpTransactionMonad

  // -----------------------------
  // JTA Transaction definitions
  // -----------------------------

  /**
   * Returns the current Transaction.
   */
  def getTransaction: Transaction = TransactionContext.getTransactionManager.getTransaction

  /**
   * Marks the current transaction as doomed.
   */
  def setRollbackOnly = TransactionContext.setRollbackOnly

  /**
   * Marks the current transaction as doomed.
   */
  def doom = TransactionContext.setRollbackOnly

  /**
   * Checks if the current transaction is doomed.
   */
  def isRollbackOnly = TransactionContext.isRollbackOnly

  /**
   * Checks that the current transaction is NOT doomed.
   */
  def isNotDoomed = !TransactionContext.isRollbackOnly
}

/**
 * Manages a thread-local stack of TransactionContexts.
 * <p/>
 * Choose TransactionService implementation by implicit definition of the implementation of choice,
 * e.g. <code>implicit val txService = TransactionServices.AtomikosTransactionService</code>.
 * <p/>
 * Example usage 1:
 * <pre>
 * for {
 *   ctx <- TransactionContext.Required
 *   entity <- updatedEntities
 *   if !ctx.isRollbackOnly
 * } {
 *   // transactional stuff
 *   ...
 * }
 * </pre>
 * Example usage 2:
 * <pre>
 * val users = for {
 *   ctx <- TransactionContext.Required
 *   name <- userNames
 * } yield {
 *   // transactional stuff
 *   ...
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionContext extends TransactionProtocol with Logging {
  // FIXME: make configurable
  private implicit val defaultTransactionService = AtomikosTransactionService

  private[TransactionContext] val stack = new scala.util.DynamicVariable(new TransactionContext)

  object Required extends TransactionMonad {
    def map[T](f: TransactionMonad => T): T =        withTxRequired { f(this) }
    def flatMap[T](f: TransactionMonad => T): T =    withTxRequired { f(this) }
    def foreach(f: TransactionMonad => Unit): Unit = withTxRequired { f(this) }
  }

  object RequiresNew extends TransactionMonad {
    def map[T](f: TransactionMonad => T): T =        withTxRequiresNew { f(this) }
    def flatMap[T](f: TransactionMonad => T): T =    withTxRequiresNew { f(this) }
    def foreach(f: TransactionMonad => Unit): Unit = withTxRequiresNew { f(this) }
  }

  object Supports extends TransactionMonad {
    def map[T](f: TransactionMonad => T): T =        withTxSupports { f(this) }
    def flatMap[T](f: TransactionMonad => T): T =    withTxSupports { f(this) }
    def foreach(f: TransactionMonad => Unit): Unit = withTxSupports { f(this) }
  }

  object Mandatory extends TransactionMonad {
    def map[T](f: TransactionMonad => T): T =        withTxMandatory { f(this) }
    def flatMap[T](f: TransactionMonad => T): T =    withTxMandatory { f(this) }
    def foreach(f: TransactionMonad => Unit): Unit = withTxMandatory { f(this) }
  }

  object Never extends TransactionMonad {
    def map[T](f: TransactionMonad => T): T =        withTxNever { f(this) }
    def flatMap[T](f: TransactionMonad => T): T =    withTxNever { f(this) }
    def foreach(f: TransactionMonad => Unit): Unit = withTxNever { f(this) }
  }

  object NoOpTransactionMonad extends TransactionMonad {
    def map[T](f: TransactionMonad => T): T =        f(this)
    def flatMap[T](f: TransactionMonad => T): T =    f(this)
    def foreach(f: TransactionMonad => Unit): Unit = f(this)
    override def filter(f: TransactionMonad => Boolean): TransactionMonad = this
  }

  private[jta] def setRollbackOnly = current.setRollbackOnly

  private[jta] def isRollbackOnly = current.isRollbackOnly

  private[jta] def getTransactionManager: TransactionManager = current.getTransactionManager

  private[jta] def getTransaction: Transaction = current.getTransactionManager.getTransaction

  private[this] def current = stack.value

  /**
   * Continues with the invocation defined in 'body' with the brand new context define in 'newCtx', the old
   * one is put on the stack and will automatically come back in scope when the method exits.
   * <p/>
   * Suspends and resumes the current JTA transaction.
   */
  private[jta] def withNewContext[T](body: => T): T = {
    val suspendedTx: Option[Transaction] =
      if (isInExistingTransaction(getTransactionManager)) {
        log.debug("Suspending TX")
        Some(getTransactionManager.suspend)
      } else None
    val result = stack.withValue(new TransactionContext) { body }
    if (suspendedTx.isDefined) {
      log.debug("Resuming TX")
      getTransactionManager.resume(suspendedTx.get)
    }
    result
  }
}

/**
 * Transaction context, holds the EntityManager and the TransactionManager.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionContext(private implicit val transactionService: TransactionService) {
  val tm: TransactionManager = transactionService.transactionManager
  private def setRollbackOnly = tm.setRollbackOnly
  private def isRollbackOnly: Boolean = tm.getStatus == Status.STATUS_MARKED_ROLLBACK
  private def getTransactionManager: TransactionManager = tm
}
