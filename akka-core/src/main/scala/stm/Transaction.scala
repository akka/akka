/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

import javax.transaction.{TransactionManager, UserTransaction, Status, TransactionSynchronizationRegistry}

import scala.collection.mutable.HashMap

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config._

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.api.lifecycle.{TransactionLifecycleListener, TransactionLifecycleEvent}
import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance
import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.templates.{TransactionTemplate, OrElseTemplate}
import org.multiverse.api.backoff.ExponentialBackoffPolicy
import org.multiverse.stms.alpha.AlphaStm

class NoTransactionInScopeException extends RuntimeException
class TransactionRetryException(message: String) extends RuntimeException(message)
class StmConfigurationException(message: String) extends RuntimeException(message)

object Transaction {
  val idFactory = new AtomicLong(-1L)

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
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Local extends TransactionManagement with Logging {

    /**
    * See ScalaDoc on Transaction.Local class.
     */
    def atomic[T](body: => T): T = {
      new TransactionTemplate[T]() {
        def execute(mtx: MultiverseTransaction): T = {
          Transaction.attach
          body
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
        def either(t: MultiverseTransaction) = firstBody
        def orelse(t: MultiverseTransaction) = secondBody
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
   * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
   */
  object Global extends TransactionManagement with Logging {

    /**
     * See ScalaDoc on Transaction.Global class.
     */
    def atomic[T](body: => T): T = {
      var isTopLevelTransaction = false
      new TransactionTemplate[T]() {
        def execute(mtx: MultiverseTransaction): T = {
          if (!isTransactionSetInScope) createNewTransactionSet
          Transaction.attach
          val result = body
          val txSet = getTransactionSetInScope
          log.trace("Committing transaction [%s]\n\tby joining transaction set [%s]", mtx, txSet)
          // FIXME ? txSet.tryJoinCommit(mtx, TransactionManagement.TRANSACTION_TIMEOUT, TimeUnit.MILLISECONDS)
          txSet.joinCommit(mtx)
          clearTransaction
          result
        }
      }.execute()
    }
  }

  /**
   * Attach an Akka-specific Transaction to the current Multiverse transaction.
   * Must be called within a Multiverse transaction.
   */ 
  private[akka] def attach = {
    val mtx = getRequiredThreadLocalTransaction
    val tx = new Transaction
    tx.begin
    tx.transaction = Some(mtx)
    TransactionManagement.transaction.set(Some(tx))
    mtx.registerLifecycleListener(new TransactionLifecycleListener() {
      def notify(mtx: MultiverseTransaction, event: TransactionLifecycleEvent) = event match {
        case TransactionLifecycleEvent.PostCommit => tx.commit
        case TransactionLifecycleEvent.PostAbort => tx.abort
        case _ => {}
      }
    })
  }
}

/**
 * The Akka specific Transaction class, keeping track of persistent data structures (as in on-disc)
 * and JTA support.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable class Transaction extends Logging {
  val JTA_AWARE = config.getBool("akka.stm.jta-aware", false)

  val id = Transaction.idFactory.incrementAndGet
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New
  private[akka] var transaction: Option[MultiverseTransaction] = None
  private[this] val persistentStateMap = new HashMap[String, Committable with Abortable]
  private[akka] val depth = new AtomicInteger(0)

  val jta: Option[TransactionContainer] =
    if (JTA_AWARE) Some(TransactionContainer())
    else None

  log.trace("Creating %s", toString)

  // --- public methods ---------

  def begin = synchronized {
    jta.foreach { txContainer =>
      txContainer.begin
      txContainer.registerSynchronization(new StmSynchronization(txContainer, this))
    }
  }

  def commit = synchronized {
    log.trace("Committing transaction %s", toString)
    persistentStateMap.valuesIterator.foreach(_.commit)
    status = TransactionStatus.Completed
    jta.foreach(_.commit)
  }

  def abort = synchronized {
    log.trace("Aborting transaction %s", toString)
    jta.foreach(_.rollback)
    persistentStateMap.valuesIterator.foreach(_.abort)
    persistentStateMap.clear
  }

  def isNew = synchronized { status == TransactionStatus.New }

  def isActive = synchronized { status == TransactionStatus.Active }

  def isCompleted = synchronized { status == TransactionStatus.Completed }

  def isAborted = synchronized { status == TransactionStatus.Aborted }

  // --- internal methods ---------

  private def isJtaTxActive(status: Int) = status == Status.STATUS_ACTIVE

  private[akka] def status_? = status

  private[akka] def increment = depth.incrementAndGet

  private[akka] def decrement = depth.decrementAndGet

  private[akka] def isTopLevel = depth.get == 0

  private[akka] def register(uuid: String, storage: Committable with Abortable) = persistentStateMap.put(uuid, storage)

  private def ensureIsActive = if (status != TransactionStatus.Active)
    throw new StmConfigurationException(
      "Expected ACTIVE transaction - current status [" + status + "]: " + toString)

  private def ensureIsActiveOrAborted =
    if (!(status == TransactionStatus.Active || status == TransactionStatus.Aborted))
      throw new StmConfigurationException(
        "Expected ACTIVE or ABORTED transaction - current status [" + status + "]: " + toString)

  private def ensureIsActiveOrNew =
    if (!(status == TransactionStatus.Active || status == TransactionStatus.New))
      throw new StmConfigurationException(
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

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable
trait Transactional {
  val uuid: String
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Committable {
  def commit: Unit
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Abortable {
  def abort: Unit
}
