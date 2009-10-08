/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

import se.scalablesolutions.akka.dispatch.MessageInvocation
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.state.Committable

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.stms.alpha.AlphaStm
import org.multiverse.utils.GlobalStmInstance
import org.multiverse.utils.TransactionThreadLocal._
import org.multiverse.templates.{OrElseTemplate, AtomicTemplate}

import scala.collection.mutable.HashMap

class TransactionRetryException(message: String) extends RuntimeException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Multiverse {
  val STM = new AlphaStm
  GlobalStmInstance.set(STM)
  setThreadLocalTransaction(null)
}

/**
 * Example of atomic transaction management.
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * Atomic {
 *   .. // do something within a transaction
 * }
 * </pre>
 *
 * Example of Or-Else transaction management.
 * <pre>
 * import se.scalablesolutions.akka.stm.Transaction._
 * Or {
 *   .. // try to do something
 * } Else {
 *   .. // if transaction clashes try do do something else to minimize contention
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Transaction {
  val idFactory = new AtomicLong(-1L)

  // -- Monad --------------------------

  // -- atomic block --------------------------
  def Atomic[T](body: => T) = new AtomicTemplate[T]() {
    def execute(t: MultiverseTransaction): T = body
  }.execute()


  // -- OrElse --------------------------
  def Or[A](orBody: => A) = elseBody(orBody)
  def elseBody[A](orBody: => A) = new {
    def Else(elseBody: => A) = new OrElseTemplate[A] {
      def run(t: MultiverseTransaction) = orBody
      def orelserun(t: MultiverseTransaction) = elseBody
    }.execute()
  }
} 

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable class Transaction extends Logging {
  val id = Transaction.idFactory.incrementAndGet
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New
  private[akka] var transaction: MultiverseTransaction = _

  private[this] var message: Option[MessageInvocation] = None

  private[this] var participants: List[String] = Nil
  private[this] var precommitted: List[String] = Nil

  private[this] val persistentStateMap = new HashMap[String, Committable]

  private[akka] val depth = new AtomicInteger(0)
  
  def increment = depth.incrementAndGet
  def decrement = depth.decrementAndGet
  def isTopLevel = depth.get == 0

  def register(uuid: String, storage: Committable) = persistentStateMap.put(uuid, storage)
  
  def begin(participant: String, msg: MessageInvocation) = synchronized {
    ensureIsActiveOrNew
    message = Some(msg)
    transaction = Multiverse.STM.startUpdateTransaction("akka")
    log.debug("TX BEGIN - Creating a new transaction with id [%s]", id)

    if (status == TransactionStatus.New) log.debug("TX BEGIN - Actor with UUID [%s] is starting NEW transaction [%s]", participant, toString)
    else log.debug("Actor [%s] is participating in transaction", participant)
    participants ::= participant
    status = TransactionStatus.Active
  }

  def precommit(participant: String) = synchronized {
    if (status == TransactionStatus.Active) {
      log.debug("TX PRECOMMIT - Pre-committing transaction [%s] for server with UUID [%s]", toString, participant)
      precommitted ::= participant
    }
  }

  def commit(participant: String): Boolean = synchronized {
    if (status == TransactionStatus.Active) {
      log.debug("TX COMMIT - Trying to commit transaction [%s] for server with UUID [%s]", toString, participant)
      val haveAllPreCommitted =
        if (participants.size == precommitted.size) {{
          for (part <- participants) yield {
            if (precommitted.exists(_ == part)) true
            else false
          }}.exists(_ == true)
        } else false
      if (haveAllPreCommitted && transaction != null) {
        setThreadLocalTransaction(transaction)
        log.debug("TX COMMIT - Committing transaction [%s] for server with UUID [%s]", toString, participant)
        transaction.commit
        reset
        status = TransactionStatus.Completed
        Transaction.Atomic {
          persistentStateMap.values.foreach(_.commit)          
        }
        setThreadLocalTransaction(null)
        true
      } else false
    } else {
      true
    }
  }

  def rollback(participant: String) = synchronized {
    ensureIsActiveOrAborted
    log.debug("TX ROLLBACK - Actor with UUID [%s] has initiated transaction rollback for [%s]", participant, toString)
    status = TransactionStatus.Aborted
    transaction.abort
    reset
  }
  
  def join(participant: String) = synchronized {
    ensureIsActive
    log.debug("TX JOIN - Actor with UUID [%s] is joining transaction [%s]" , participant, toString)
    participants ::= participant
  }

  def retry: Boolean = synchronized {
    println("----- 2 " + message.isDefined)
    println("----- 3 " + message.get.nrOfDeliveryAttempts)
    if (message.isDefined && message.get.nrOfDeliveryAttempts.get < TransactionManagement.MAX_NR_OF_RETRIES) { 
      log.debug("TX RETRY - Restarting transaction [%s] resending message [%s]", transaction, message.get)
      message.get.send
      true
    } else false
  }

  private def reset = synchronized {
    transaction.reset
    participants = Nil
    precommitted = Nil
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
