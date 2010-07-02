/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

import javax.transaction.{TransactionManager, UserTransaction, Status, TransactionSynchronizationRegistry}

import scala.collection.mutable.HashMap

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config._

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.api.lifecycle.{TransactionLifecycleListener, TransactionLifecycleEvent}
import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.api.{TraceLevel => MultiverseTraceLevel}

class NoTransactionInScopeException extends RuntimeException
class TransactionRetryException(message: String) extends RuntimeException(message)
class StmConfigurationException(message: String) extends RuntimeException(message)

object Transaction {
  val idFactory = new AtomicLong(-1L)

  @deprecated("Use the se.scalablesolutions.akka.stm.local package object instead.")
  object Local extends LocalStm

  @deprecated("Use the se.scalablesolutions.akka.stm.global package object instead.")
  object Global extends GlobalStm

  object Util extends StmUtil

  /**
   * Attach an Akka-specific Transaction to the current Multiverse transaction.
   * Must be called within a Multiverse transaction. Used by TransactionFactory.addHooks
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

  /**
   * Mapping to Multiverse TraceLevel.
   */
  object TraceLevel {
    val None = MultiverseTraceLevel.none
    val Coarse = MultiverseTraceLevel.course // mispelling?
    val Course = MultiverseTraceLevel.course
    val Fine = MultiverseTraceLevel.fine
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
  def commit(): Unit
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Abortable {
  def abort(): Unit
}

