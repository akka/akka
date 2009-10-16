/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

import se.scalablesolutions.akka.state.Committable
import se.scalablesolutions.akka.util.Logging

import org.multiverse.api.{Stm, Transaction => MultiverseTransaction}
import org.multiverse.stms.alpha.AlphaStm
import org.multiverse.utils.GlobalStmInstance
import org.multiverse.utils.TransactionThreadLocal._
import org.multiverse.templates.OrElseTemplate

import scala.collection.mutable.HashMap

class NoTransactionInScopeException extends RuntimeException
class TransactionRetryException(message: String) extends RuntimeException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Multiverse {
  val STM: Stm = new AlphaStm
  GlobalStmInstance.set(STM)
  setThreadLocalTransaction(null)
}

/**
 * Example of atomic transaction management.
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * atomic {
 *   .. // do something within a transaction
 * }
 * </pre>
 *
 * Example of Run-OrElse transaction management.
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * run {
 *   .. // try to do something
 * } orElse {
 *   .. // if transaction clashes try do do something else to minimize contention
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Transaction extends TransactionManagement {
  val idFactory = new AtomicLong(-1L)

  // -- Monad --------------------------
  // FIXME implement Transaction::map/flatMap/filter/foreach

  // -- atomic block --------------------------
  def atomic[T](body: => T): T = new AtomicTemplate[T](Multiverse.STM, "akka", false, false, TransactionManagement.MAX_NR_OF_RETRIES) {
    def execute(mtx: MultiverseTransaction): T = body
    override def postStart(mtx: MultiverseTransaction) = {
      println("------ SETTING TX")
      val tx = new Transaction
      tx.transaction = Some(mtx)
      setTransaction(Some(tx))
    }
    override def postCommit =  {
      println("------ GETTING TX")
      if (isTransactionInScope) {}///getTransactionInScope.commit
      else throw new IllegalStateException("No transaction in scope")
    }
  }.execute()

/*
  def atomic[T](retryCount: Int)(body: => T): T = new AtomicTemplate[T](Multiverse.STM, "akka", false, false, retryCount) {
    def execute(mtx: MultiverseTransaction): T = body
    override def postCommit =
      if (isTransactionInScope) getTransactionInScope.commit
      else throw new IllegalStateException("No transaction in scope")
  }.execute

  def atomicReadOnly[T](retryCount: Int)(body: => T): T = new AtomicTemplate[T](Multiverse.STM, "akka", false, true, retryCount) {
    def execute(mtx: MultiverseTransaction): T = body
    override def postCommit =
      if (isTransactionInScope) getTransactionInScope.commit
      else throw new IllegalStateException("No transaction in scope")
  }.execute

  def atomicReadOnly[T](body: => T): T = new AtomicTemplate[T](true) {
    def execute(mtx: MultiverseTransaction): T = body
    override def postCommit =
      if (isTransactionInScope) getTransactionInScope.commit
      else throw new IllegalStateException("No transaction in scope")
  }.execute
*/
  // -- Run-OrElse --------------------------
  def run[A](orBody: => A) = elseBody(orBody)
  def elseBody[A](orBody: => A) = new {
    def orElse(elseBody: => A) = new OrElseTemplate[A] {
      def run(t: MultiverseTransaction) = orBody
      def orelserun(t: MultiverseTransaction) = elseBody
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
  
  def increment = depth.incrementAndGet
  def decrement = depth.decrementAndGet
  def isTopLevel = depth.get == 0

  def register(uuid: String, storage: Committable) = persistentStateMap.put(uuid, storage)

  def commit = synchronized {
    atomic {
      persistentStateMap.values.foreach(_.commit)
      TransactionManagement.clearTransaction
    }
    status = TransactionStatus.Completed
  }

  def status_? = status
  def isNew = synchronized { status == TransactionStatus.New }
  def isActive = synchronized { status == TransactionStatus.Active }
  def isCompleted = synchronized { status == TransactionStatus.Completed }
  def isAborted = synchronized { status == TransactionStatus.Aborted }

  private def ensureIsActive = if (status != TransactionStatus.Active)
    throw new IllegalStateException("Expected ACTIVE transaction - current status [" + status + "]: " + toString)

  private def ensureIsActiveOrAborted = if (!(status == TransactionStatus.Active || status == TransactionStatus.Aborted))
    throw new IllegalStateException("Expected ACTIVE or ABORTED transaction - current status [" + status + "]: " + toString)

  private def ensureIsActiveOrNew = if (!(status == TransactionStatus.Active || status == TransactionStatus.New))
    throw new IllegalStateException("Expected ACTIVE or NEW transaction - current status [" + status + "]: " + toString)

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


@serializable sealed abstract class TransactionStatus
object TransactionStatus {
  case object New extends TransactionStatus
  case object Active extends TransactionStatus
  case object Aborted extends TransactionStatus
  case object Completed extends TransactionStatus
}
