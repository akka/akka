/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.jta

import javax.transaction.{Transaction, Status, TransactionManager, Synchronization}

import akka.util.Logging
import akka.config.Config._

/**
 * The TransactionContext object manages the transactions.
 * Can be used as higher-order functional 'atomic blocks' or monadic.
 *
 * Manages a thread-local stack of TransactionContexts.
 * <p/>
 * Example usage 1:
 * <pre>
 * import TransactionContext._
 *
 * withTxRequired {
 *   ... // transactional stuff
 * }
 * // or
 * withTxRequiresNew {
 *   ... // transactional stuff
 * }
 * </pre>
 * Example usage 2:
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
 * Example usage 3:
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
  implicit val tc = TransactionContainer()

  private[TransactionContext] val stack = new scala.util.DynamicVariable(new TransactionContext(tc))

  /**
   * This method can be used to register a Synchronization instance for participating with the JTA transaction.
   * Here is an example of how to add a JPA EntityManager integration.
   * <pre>
   *   TransactionContext.registerSynchronization(new javax.transaction.Synchronization() {
   *     def beforeCompletion = {
   *       try {
   *         val status = tm.getStatus
   *         if (status != Status.STATUS_ROLLEDBACK &&
   *             status != Status.STATUS_ROLLING_BACK &&
   *             status != Status.STATUS_MARKED_ROLLBACK) {
   *           log.debug("Flushing EntityManager...")
   *           em.flush // flush EntityManager on success
   *         }
   *       } catch {
   *         case e: javax.transaction.SystemException => throw new RuntimeException(e)
   *       }
   *     }
   *
   *     def afterCompletion(status: Int) = {
   *       val status = tm.getStatus
   *       if (closeAtTxCompletion) em.close
   *       if (status == Status.STATUS_ROLLEDBACK ||
   *           status == Status.STATUS_ROLLING_BACK ||
   *           status == Status.STATUS_MARKED_ROLLBACK) {
   *         em.close
   *       }
   *     }
   *   })
   * </pre>
   * You should also override the 'joinTransaction' and 'handleException' methods.
   * See ScalaDoc for these methods in the 'TransactionProtocol' for details.
   */
  def registerSynchronization(sync: Synchronization) = synchronization.add(sync)

  /**
   * Registeres a join transaction function.
   * <p/>
   * Here is an example on how to integrate with JPA EntityManager.
   *
   * <pre>
   * TransactionContext.registerJoinTransactionFun(() => {
   *   val em: EntityManager = ... // get the EntityManager
   *   em.joinTransaction // join JTA transaction
   * })
   * </pre>
   */
  def registerJoinTransactionFun(fn: () => Unit) = joinTransactionFuns.add(fn)

  /**
   * Handle exception. Can be overriden by concrete transaction service implementation.
   * <p/>
   * Here is an example on how to handle JPA exceptions.
   *
   * <pre>
   * TransactionContext.registerExceptionNotToRollbackOn(classOf[NoResultException])
   * TransactionContext.registerExceptionNotToRollbackOn(classOf[NonUniqueResultException])
   * </pre>
   */
  def registerExceptionNotToRollbackOn(e: Class[_ <: Exception]) = exceptionsNotToRollbackOn.add(e)

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

  private[jta] def getTransactionContainer: TransactionContainer = current.getTransactionContainer

  private[this] def current = stack.value

  /**
   * Continues with the invocation defined in 'body' with the brand new context define in 'newCtx', the old
   * one is put on the stack and will automatically come back in scope when the method exits.
   * <p/>
   * Suspends and resumes the current JTA transaction.
   */
  private[jta] def withNewContext[T](body: => T): T = {
    val suspendedTx: Option[Transaction] =
      if (getTransactionContainer.isInExistingTransaction) {
        log.debug("Suspending TX")
        Some(getTransactionContainer.suspend)
      } else None
    val result = stack.withValue(new TransactionContext(tc)) { body }
    if (suspendedTx.isDefined) {
      log.debug("Resuming TX")
      getTransactionContainer.resume(suspendedTx.get)
    }
    result
  }
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
 * Transaction context, holds the EntityManager and the TransactionManager.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionContext(val tc: TransactionContainer) {
  def registerSynchronization(sync: Synchronization) = TransactionContext.registerSynchronization(sync)
  def setRollbackOnly = tc.setRollbackOnly
  def isRollbackOnly: Boolean = tc.getStatus == Status.STATUS_MARKED_ROLLBACK
  def getTransactionContainer: TransactionContainer = tc
}
