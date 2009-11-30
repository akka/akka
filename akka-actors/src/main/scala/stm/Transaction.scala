/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

import se.scalablesolutions.akka.state.Committable
import se.scalablesolutions.akka.util.Logging

import org.multiverse.api.{Stm, Transaction => MultiverseTransaction}
import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance
import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.templates.OrElseTemplate

import scala.collection.mutable.HashMap

class NoTransactionInScopeException extends RuntimeException
class TransactionRetryException(message: String) extends RuntimeException(message)

/**
 * Example of atomic transaction management using the atomic block:
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * 
 * atomic {
 *   .. // do something within a transaction
 * }
 * </pre>
 *
 * Example of atomic transaction management using atomic block with retry count:
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * 
 * atomic(maxNrOfRetries) {
 *   .. // do something within a transaction
 * }
 * </pre>
 *
 * Example of atomically-orElse transaction management. 
 * Which is a good way to reduce contention and transaction collisions.
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * 
 * atomically {
 *   .. // try to do something
 * } orElse {
 *   .. // if transaction clashes try do do something else to minimize contention
 * }
 * </pre>
 *
 * Example of atomic transaction management using for comprehensions (monadic):
 *
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * for (tx <- Transaction) {
 *   ... // do transactional stuff
 * }
 *
 * val result = for (tx <- Transaction) yield {
 *   ... // do transactional stuff yielding a result
 * }
 * </pre>
 *
 * Example of using Transaction and TransactionalRef in for comprehensions (monadic):
 *
 * <pre>
 * // For example, if you have a List with TransactionalRef
 * val refs: List[TransactionalRef] = ...
 *
 * // You can use them together with Transaction in a for comprehension since
 * // TransactionalRef is also monadic
 * for {
 *   tx <- Transaction
 *   ref <- refs
 * } {
 *   ... // use the ref inside a transaction
 * }
 *
 * val result = for {
 *   tx <- Transaction
 *   ref <- refs
 * } yield {
 *   ... // use the ref inside a transaction, yield a result
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Transaction extends TransactionManagement {
  val idFactory = new AtomicLong(-1L)

  /**
   * See ScalaDoc on class.
   */
  def map[T](f: Transaction => T): T = atomic { f(getTransactionInScope) }

  /**
   * See ScalaDoc on class.
   */
  def flatMap[T](f: Transaction => T): T = atomic { f(getTransactionInScope) }

  /**
   * See ScalaDoc on class.
   */
  def foreach(f: Transaction => Unit): Unit = atomic { f(getTransactionInScope) }

  /**
   * Creates a "pure" STM atomic transaction and by-passes all transactions hooks
   * such as persistence etc.
   * Only for internal usage.
   */
  private[akka] def pureAtomic[T](body: => T): T = new AtomicTemplate[T](
    getGlobalStmInstance, "akka", false, false, TransactionManagement.MAX_NR_OF_RETRIES) {
    def execute(mtx: MultiverseTransaction): T = body
  }.execute()

  /**
   * See ScalaDoc on class.
   */
  def atomic[T](body: => T): T = new AtomicTemplate[T](
    getGlobalStmInstance, "akka", false, false, TransactionManagement.MAX_NR_OF_RETRIES) {
    def execute(mtx: MultiverseTransaction): T = body
    override def postStart(mtx: MultiverseTransaction) = {
      val tx = new Transaction
      tx.transaction = Some(mtx)
      setTransaction(Some(tx))
    }
    override def postCommit =  {
      if (isTransactionInScope) getTransactionInScope.commit
      else throw new IllegalStateException("No transaction in scope")
    }
  }.execute()

  /**
   * See ScalaDoc on class.
   */
  def atomic[T](retryCount: Int)(body: => T): T = {
    new AtomicTemplate[T](getGlobalStmInstance, "akka", false, false, retryCount) {
      def execute(mtx: MultiverseTransaction): T = body
      override def postStart(mtx: MultiverseTransaction) = {
        val tx = new Transaction
        tx.transaction = Some(mtx)
        setTransaction(Some(tx))
      }
      override def postCommit =  {
        if (isTransactionInScope) getTransactionInScope.commit
        else throw new IllegalStateException("No transaction in scope")
      }
    }.execute
  }

  /**
   * See ScalaDoc on class.
   */
  def atomicReadOnly[T](retryCount: Int)(body: => T): T = {
    new AtomicTemplate[T](getGlobalStmInstance, "akka", false, true, retryCount) {
      def execute(mtx: MultiverseTransaction): T = body
      override def postStart(mtx: MultiverseTransaction) = {
        val tx = new Transaction
        tx.transaction = Some(mtx)
        setTransaction(Some(tx))
      }
      override def postCommit =  {
        if (isTransactionInScope) getTransactionInScope.commit
        else throw new IllegalStateException("No transaction in scope")
      }
    }.execute
  }

  /**
   * See ScalaDoc on class.
   */
  def atomicReadOnly[T](body: => T): T = {
    new AtomicTemplate[T](true) {
      def execute(mtx: MultiverseTransaction): T = body
      override def postStart(mtx: MultiverseTransaction) = {
        val tx = new Transaction
        tx.transaction = Some(mtx)
        setTransaction(Some(tx))
      }
      override def postCommit =  {
        if (isTransactionInScope) getTransactionInScope.commit
        else throw new IllegalStateException("No transaction in scope")
      }
    }.execute
  }

  /**
   * See ScalaDoc on class.
   */
  def atomically[A](firstBody: => A) = elseBody(firstBody)

  /**
   * Should only be used together with <code>atomically</code> to form atomically-orElse constructs.
   * See ScalaDoc on class.
   */
  def elseBody[A](firstBody: => A) = new {
    def orElse(secondBody: => A) = new OrElseTemplate[A] {
      def run(t: MultiverseTransaction) = firstBody
      def orelserun(t: MultiverseTransaction) = secondBody
    }.execute()
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable class Transaction extends Logging {
  import Transaction._
  
  val id = Transaction.idFactory.incrementAndGet
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New
  private[akka] var transaction: Option[MultiverseTransaction] = None
  private[this] val persistentStateMap = new HashMap[String, Committable]
  private[akka] val depth = new AtomicInteger(0)
  
  // --- public methods ---------

  def commit = synchronized {
    pureAtomic {
      persistentStateMap.values.foreach(_.commit)
      TransactionManagement.clearTransaction
    }
    status = TransactionStatus.Completed
  }

  def isNew = synchronized { status == TransactionStatus.New }

  def isActive = synchronized { status == TransactionStatus.Active }

  def isCompleted = synchronized { status == TransactionStatus.Completed }

  def isAborted = synchronized { status == TransactionStatus.Aborted }

  // --- internal methods ---------

  private[akka] def status_? = status

  private[akka] def increment = depth.incrementAndGet

  private[akka] def decrement = depth.decrementAndGet

  private[akka] def isTopLevel = depth.get == 0

  private[akka] def register(uuid: String, storage: Committable) = persistentStateMap.put(uuid, storage)

  private def ensureIsActive = if (status != TransactionStatus.Active)
    throw new IllegalStateException(
      "Expected ACTIVE transaction - current status [" + status + "]: " + toString)

  private def ensureIsActiveOrAborted =
    if (!(status == TransactionStatus.Active || status == TransactionStatus.Aborted))
    throw new IllegalStateException(
      "Expected ACTIVE or ABORTED transaction - current status [" + status + "]: " + toString)

  private def ensureIsActiveOrNew =
    if (!(status == TransactionStatus.Active || status == TransactionStatus.New))
    throw new IllegalStateException(
      "Expected ACTIVE or NEW transaction - current status [" + status + "]: " + toString)

  // For reinitialize transaction after sending it over the wire 
  private[akka] def reinit = synchronized {
    import net.lag.logging.{Logger, Level}
    if (log == null) {
      log = Logger.get(this.getClass.getName)
      log.setLevel(Level.ALL) // TODO: preserve logging level
    }
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null && 
    that.isInstanceOf[Transaction] && 
    that.asInstanceOf[Transaction].id == this.id
  }
 
  override def hashCode(): Int = synchronized { id.toInt }
 
  override def toString(): String = synchronized { "Transaction[" + id + ", " + status + "]" }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable sealed abstract class TransactionStatus

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionStatus {
  case object New extends TransactionStatus
  case object Active extends TransactionStatus
  case object Aborted extends TransactionStatus
  case object Completed extends TransactionStatus
}

