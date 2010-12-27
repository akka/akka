/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.HashMap

import akka.util.{Logging, ReflectiveAccess}
import akka.config.Config._
import akka.config.ModuleNotAvailableException
import akka.AkkaException

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.api.lifecycle.{TransactionLifecycleListener, TransactionLifecycleEvent}
import org.multiverse.api.ThreadLocalTransaction._
import org.multiverse.api.{PropagationLevel => MultiversePropagationLevel}
import org.multiverse.api.{TraceLevel => MultiverseTraceLevel}

class NoTransactionInScopeException extends AkkaException("No transaction in scope")
class TransactionRetryException(message: String) extends AkkaException(message)
class StmConfigurationException(message: String) extends AkkaException(message)


/**
 * Internal helper methods for managing Akka-specific transaction.
 */
object TransactionManagement extends TransactionManagement {
  private[akka] val transaction = new ThreadLocal[Option[Transaction]]() {
    override protected def initialValue: Option[Transaction] = None
  }

  private[akka] def getTransaction: Transaction = {
    val option = transaction.get
    if ((option eq null) || option.isEmpty) throw new StmConfigurationException("No Transaction in scope")
    option.get
  }
}

/**
 * Internal helper methods for managing Akka-specific transaction.
 */
trait TransactionManagement {
  private[akka] def setTransaction(tx: Option[Transaction]) =
    if (tx.isDefined) TransactionManagement.transaction.set(tx)

  private[akka] def clearTransaction = {
    TransactionManagement.transaction.set(None)
    setThreadLocalTransaction(null)
  }

  private[akka] def getTransactionInScope = TransactionManagement.getTransaction

  private[akka] def isTransactionInScope = {
    val option = TransactionManagement.transaction.get
    (option ne null) && option.isDefined
  }
}

object Transaction {
  val idFactory = new AtomicLong(-1L)

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
        case TransactionLifecycleEvent.PostCommit => tx.commitJta
        case TransactionLifecycleEvent.PreCommit => tx.commitPersistentState
        case TransactionLifecycleEvent.PostAbort => tx.abort
        case _ => {}
      }
    })
  }
}

/**
 * The Akka-specific Transaction class.
 * For integration with persistence modules and JTA support.
 */
@serializable class Transaction extends Logging {
  val JTA_AWARE = config.getBool("akka.stm.jta-aware", false)
  val STATE_RETRIES = config.getInt("akka.storage.max-retries",10)

  val id = Transaction.idFactory.incrementAndGet
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New
  private[akka] var transaction: Option[MultiverseTransaction] = None
  private[this] val persistentStateMap = new HashMap[String, Committable with Abortable]
  private[akka] val depth = new AtomicInteger(0)

  val jta: Option[ReflectiveJtaModule.TransactionContainer] =
    if (JTA_AWARE) Some(ReflectiveJtaModule.createTransactionContainer)
    else None

  log.slf4j.trace("Creating transaction " + toString)

  // --- public methods ---------

  def begin = synchronized {
    log.slf4j.trace("Starting transaction " + toString)
    jta.foreach { _.beginWithStmSynchronization(this) }
  }

  def commitPersistentState = synchronized {
    log.trace("Committing transaction " + toString)
    retry(STATE_RETRIES){
      persistentStateMap.valuesIterator.foreach(_.commit)
      persistentStateMap.clear
    }
    status = TransactionStatus.Completed
  }

  def commitJta = synchronized {
    jta.foreach(_.commit)
  }

  def abort = synchronized {
    log.slf4j.trace("Aborting transaction " + toString)
    jta.foreach(_.rollback)
    persistentStateMap.valuesIterator.foreach(_.abort)
    persistentStateMap.clear
  }

  def retry(tries:Int)(block: => Unit):Unit={
    log.debug("Trying commit of persistent data structures")
    if(tries==0){
      throw new TransactionRetryException("Exhausted Retries while committing persistent state")
    }
    try{
      block
    } catch{
      case e:Exception=>{
        log.warn(e,"Exception while committing persistent state, retrying")
        retry(tries-1){block}
      }
    }
  }

  def isNew = synchronized { status == TransactionStatus.New }

  def isActive = synchronized { status == TransactionStatus.Active }

  def isCompleted = synchronized { status == TransactionStatus.Completed }

  def isAborted = synchronized { status == TransactionStatus.Aborted }

  // --- internal methods ---------

  //private def isJtaTxActive(status: Int) = status == Status.STATUS_ACTIVE

  private[akka] def status_? = status

  private[akka] def increment = depth.incrementAndGet

  private[akka] def decrement = depth.decrementAndGet

  private[akka] def isTopLevel = depth.get == 0
  //when calling this method, make sure to prefix the uuid with the type so you
  //have no possibility of kicking a diffferent type with the same uuid out of a transction
  private[akka] def register(uuid: String, storage: Committable with Abortable) = {
    if(persistentStateMap.getOrElseUpdate(uuid, {storage}) ne storage){
      log.error("existing:"+System.identityHashCode(persistentStateMap.get(uuid).get))
      log.error("new:"+System.identityHashCode(storage))
      throw new IllegalStateException("attempted to register an instance of persistent data structure for id [%s] when there is already a different instance registered".format(uuid))
    }
  }

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

  override def equals(that: Any): Boolean = synchronized {
    that.isInstanceOf[Transaction] &&
    that.asInstanceOf[Transaction].id == this.id
  }

  override def hashCode: Int = synchronized { id.toInt }

  override def toString = synchronized { "Transaction[" + id + ", " + status + "]" }
}

@serializable sealed abstract class TransactionStatus

object TransactionStatus {
  case object New extends TransactionStatus
  case object Active extends TransactionStatus
  case object Aborted extends TransactionStatus
  case object Completed extends TransactionStatus
}

/**
 * Common trait for all the transactional objects:
 * Ref, TransactionalMap, TransactionalVector,
 * PersistentRef, PersistentMap, PersistentVector, PersistentQueue, PersistentSortedSet
 */
@serializable trait Transactional {
  val uuid: String
}

/**
 * Used for integration with the persistence modules.
 */
trait Committable {
  def commit(): Unit
}

/**
 * Used for integration with the persistence modules.
 */
trait Abortable {
  def abort(): Unit
}

/**
 * Used internally for reflective access to the JTA module.
 * Allows JTA integration to work when akka-jta.jar is on the classpath.
 */
object ReflectiveJtaModule {
  type TransactionContainerObject = {
    def apply(): TransactionContainer
  }

  type TransactionContainer = {
    def beginWithStmSynchronization(transaction: Transaction): Unit
    def commit: Unit
    def rollback: Unit
  }

  lazy val isJtaEnabled = transactionContainerObjectInstance.isDefined

  def ensureJtaEnabled = if (!isJtaEnabled) throw new ModuleNotAvailableException(
    "Can't load the JTA module, make sure that akka-jta.jar is on the classpath")

  val transactionContainerObjectInstance: Option[TransactionContainerObject] =
    ReflectiveAccess.getObjectFor("akka.jta.TransactionContainer$")

  def createTransactionContainer: TransactionContainer = {
    ensureJtaEnabled
    transactionContainerObjectInstance.get.apply.asInstanceOf[TransactionContainer]
  }
}
