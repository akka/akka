/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

import scala.collection.mutable.HashMap

import se.scalablesolutions.akka.util.Logging

import org.multiverse.api.{Transaction => MultiverseTransaction, TransactionLifecycleListener, TransactionLifecycleEvent}
import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance
import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.templates.{TransactionTemplate, OrElseTemplate}
import org.multiverse.utils.backoff.ExponentialBackoffPolicy
import org.multiverse.stms.alpha.AlphaStm

class NoTransactionInScopeException extends RuntimeException
class TransactionRetryException(message: String) extends RuntimeException(message)

object Transaction {
  val idFactory = new AtomicLong(-1L)

  /**
   * Creates a STM atomic transaction and by-passes all transactions hooks
   * such as persistence etc.
   *
   * Only for internal usage.
   */
  private[akka] def atomic0[T](body: => T): T = new TransactionTemplate[T]() {
    def execute(mtx: MultiverseTransaction): T = body
  }.execute()

  /**
   * Module for "local" transaction management, local in the context of threads.
   * You should only use these if you do <b>not</b> need to have one transaction span
   * multiple threads (or Actors).
   * <p/>
   * Example of atomic transaction management using the atomic block.
   * <p/>
   * <pre>
   * import se.scalablesolutions.akka.stm.Transaction.Local._
   *
   * atomic  {
   *   .. // do something within a transaction
   * }
   * </pre>
   *
   * Example of atomically-orElse transaction management.
   * Which is a good way to reduce contention and transaction collisions.
   * <pre>
   * import se.scalablesolutions.akka.stm.Transaction.Local._
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
   * import se.scalablesolutions.akka.stm.Transaction.Local._
   * for (tx <- Transaction.Local)  {
   *   ... // do transactional stuff
   * }
   *
   * val result = for (tx <- Transaction.Local) yield  {
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
   *   tx <- Transaction.Local
   *   ref <- refs
   * } {
   *   ... // use the ref inside a transaction
   * }
   *
   * val result = for  {
   *   tx <- Transaction.Local
   *   ref <- refs
   * } yield  {
   *   ... // use the ref inside a transaction, yield a result
   * }
   * </pre>
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Local extends TransactionManagement with Logging {

    /**
     * See ScalaDoc on Transaction.Local class.
     */
    def map[T](f: => T): T = atomic {f}

    /**
    * See ScalaDoc on Transaction.Local class.
     */
    def flatMap[T](f: => T): T = atomic {f}

    /**
    * See ScalaDoc on Transaction.Local class.
     */
    def foreach(f: => Unit): Unit = atomic {f}

    /**
    * See ScalaDoc on Transaction.Local class.
     */
    def atomic[T](body: => T): T = {
      new TransactionTemplate[T]() {
        def execute(mtx: MultiverseTransaction): T = body

        override def onStart(mtx: MultiverseTransaction) = {
          val tx = new Transaction
          tx.transaction = Some(mtx)
          setTransaction(Some(tx))
          mtx.registerLifecycleListener(new TransactionLifecycleListener() {
            def notify(tx: MultiverseTransaction, event: TransactionLifecycleEvent) = event.name match {
              case "postCommit" => tx.commit
              case "postAbort" => tx.abort
              case _ => {}
            }
          })
        }
      }.execute()
    }

    /**
    * See ScalaDoc on Transaction.Local class.
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
   * Module for "global" transaction management, global in the context of multiple threads.
   * You have to use these if you do need to have one transaction span multiple threads (or Actors).
   * <p/>
   * Example of atomic transaction management using the atomic block.
   * <p/>
   * Here are some examples (assuming implicit transaction family name in scope):
   * <pre>
   * import se.scalablesolutions.akka.stm.Transaction.Global._
   *
   * atomic  {
   *   .. // do something within a transaction
   * }
   * </pre>
   *
   * Example of atomic transaction management using for comprehensions (monadic):
   *
   * <pre>
   * import se.scalablesolutions.akka.stm.Transaction
   * for (tx <- Transaction.Global)  {
   *   ... // do transactional stuff
   * }
   *
   * val result = for (tx <- Transaction.Global) yield  {
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
   *   tx <- Transaction.Global
   *   ref <- refs
   * } {
   *   ... // use the ref inside a transaction
   * }
   *
   * val result = for  {
   *   tx <- Transaction.Global
   *   ref <- refs
   * } yield  {
   *   ... // use the ref inside a transaction, yield a result
   * }
   * </pre>
   *
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Global extends TransactionManagement with Logging {

    /**
     * See ScalaDoc on Transaction.Global class.
     */
    def map[T](f: => T): T = atomic {f}

    /**
    * See ScalaDoc on Transaction.Global class.
     */
    def flatMap[T](f: => T): T = atomic {f}

    /**
    * See ScalaDoc on Transaction.Global class.
     */
    def foreach(f: => Unit): Unit = atomic {f}

    /**
     * See ScalaDoc on Transaction.Global class.
     */
    def atomic[T](body: => T): T = {
      var isTopLevelTransaction = false
      new TransactionTemplate[T]() {
        def execute(mtx: MultiverseTransaction): T = {
          val result = body

          val txSet = getTransactionSetInScope
          log.trace("Committing transaction [%s]\n\tby joining transaction set [%s]", mtx, txSet)
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
  }
}

/**
 * The Akka specific Transaction class, keeping track of persistent data structures (as in on-disc).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable class Transaction extends Logging {
  val id = Transaction.idFactory.incrementAndGet
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New
  private[akka] var transaction: Option[MultiverseTransaction] = None
  private[this] val persistentStateMap = new HashMap[String, Committable]
  private[akka] val depth = new AtomicInteger(0)

  log.trace("Creating %s", toString)

  // --- public methods ---------

  def commit = synchronized {
    log.trace("Committing transaction %s", toString)
    Transaction.atomic0 {
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

