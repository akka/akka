/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

import scala.collection.mutable.HashMap

import se.scalablesolutions.akka.util.Logging

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance
import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.templates.{TransactionTemplate, OrElseTemplate}
import org.multiverse.utils.backoff.ExponentialBackoffPolicy
import org.multiverse.stms.alpha.AlphaStm

class NoTransactionInScopeException extends RuntimeException
class TransactionRetryException(message: String) extends RuntimeException(message)

/**
 * Example of atomic transaction management using the atomic block.
 * These blocks takes an implicit argument String defining the transaction family name.
 * If these blocks are used from within an Actor then the name is automatically resolved, if not either:
 * 1. define an implicit String with the name in the same scope
 * 2. pass in the name explicitly
 *
 * Here are some examples (assuming implicit transaction family name in scope): 
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 *
 * atomic  {
 *   .. // do something within a transaction
 * }
 * </pre>
 *
 * Example of atomic transaction management using atomic block with retry count:
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 *
 * atomic(maxNrOfRetries)  {
 *   .. // do something within a transaction
 * }
 * </pre>
 *
 * Example of atomically-orElse transaction management. 
 * Which is a good way to reduce contention and transaction collisions.
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 *
 * atomically  {
 *   .. // try to do something
 * } orElse  {
 *   .. // if transaction clashes try do do something else to minimize contention
 * }
 * </pre>
 *
 * Example of atomic transaction management using for comprehensions (monadic):
 *
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * for (tx <- Transaction)  {
 *   ... // do transactional stuff
 * }
 *
 * val result = for (tx <- Transaction) yield  {
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
 * for  {
 *   tx <- Transaction
 *   ref <- refs
 * } {
 *   ... // use the ref inside a transaction
 * }
 *
 * val result = for  {
 *   tx <- Transaction
 *   ref <- refs
 * } yield  {
 *   ... // use the ref inside a transaction, yield a result
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Transaction extends TransactionManagement with Logging {
  val idFactory = new AtomicLong(-1L)

  /**
   * See ScalaDoc on Transaction class.
   */
  def map[T](f: => T)(implicit transactionFamilyName: String): T =
    atomic {f}

  /**
   * See ScalaDoc on Transaction class.
   */
  def flatMap[T](f: => T)(implicit transactionFamilyName: String): T =
    atomic {f}

  /**
   * See ScalaDoc on Transaction class.
   */
  def foreach(f: => Unit)(implicit transactionFamilyName: String): Unit =
    atomic {f}

  /**
   * See ScalaDoc on Transaction class.
   */
  def atomic[T](body: => T)(implicit transactionFamilyName: String): T = {
    var isTopLevelTransaction = true
    new TransactionTemplate[T]() {
      def execute(mtx: MultiverseTransaction): T = {
        val result = body

        val txSet = getTransactionSetInScope
        log.trace("Committing transaction [%s]\n\twith family name [%s]\n\tby joining transaction set [%s]", 
                  mtx, transactionFamilyName, txSet)
        txSet.joinCommit(mtx)

        // FIXME tryJoinCommit(mtx, TransactionManagement.TRANSACTION_TIMEOUT, TimeUnit.MILLISECONDS) 
        //getTransactionSetInScope.tryJoinCommit(mtx, TransactionManagement.TRANSACTION_TIMEOUT, TimeUnit.MILLISECONDS)

        clearTransaction
        result
      }

      override def onStart(mtx: MultiverseTransaction) = {
        val txSet = 
          if (!isTransactionSetInScope) {
            isTopLevelTransaction = true
            createNewTransactionSet
          } else getTransactionSetInScope
        val tx = new Transaction
        tx.transaction = Some(mtx)
        setTransaction(Some(tx))

        txSet.registerOnCommitTask(new Runnable() {
          def run = tx.commit
        })
        txSet.registerOnAbortTask(new Runnable() {
          def run = tx.abort
        })
      }
    }.execute()
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

  /**
   * Creates a STM atomic transaction and by-passes all transactions hooks
   * such as persistence etc.
   * 
   * Only for internal usage.
   */
  private[akka] def atomic0[T](body: => T): T = new TransactionTemplate[T]() {
    def execute(mtx: MultiverseTransaction): T = body
  }.execute()
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

  log.trace("Creating %s", toString)

  // --- public methods ---------

  def commit = synchronized {
    log.trace("Committing transaction %s", toString)    
    atomic0 {
      persistentStateMap.valuesIterator.foreach(_.commit)
    }
    status = TransactionStatus.Completed
  }

  def abort = synchronized {
    log.trace("Aborting transaction %s", toString)    
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
/*  private[akka] def reinit = synchronized {
    import net.lag.logging.{Logger, Level}
    if (log eq null) {
      log = Logger.get(this.getClass.getName)
      log.setLevel(Level.ALL) // TODO: preserve logging level
    }
  }
*/
  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[Transaction] &&
    that.asInstanceOf[Transaction].id == this.id
  }

  override def hashCode: Int = synchronized { id.toInt }

  override def toString = synchronized { "Transaction[" + id + ", " + status + "]" }
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

