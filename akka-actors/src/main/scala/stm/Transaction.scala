/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.stm

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import state.Transactional
import util.Logging

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.stms.alpha.AlphaStm
import org.multiverse.utils.GlobalStmInstance
import org.multiverse.utils.TransactionThreadLocal._
import org.multiverse.templates.{OrElseTemplate, AtomicTemplate}

import java.util.concurrent.atomic.AtomicInteger

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Multiverse {
  val STM = new AlphaStm
  GlobalStmInstance.set(STM)
  setThreadLocalTransaction(null)
}

/**
 * Example of Or-Else transaction management.
 * <pre>
 * import se.scalablesolutions.akka.stm.{Transaction => Tx}
 * Tx.Or {
 *   .. // try to do something
 * } Tx.Else {
 *   .. // if transaction clashes try do do something else to minimize contention
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Transaction {

  // -- Monad --------------------------


  // -- OrElse --------------------------
  def Or[A](orBody: => A) = elseBody(orBody)
  def elseBody[A](orBody: => A) = new {
    def Else(elseBody: => A) = new OrElseTemplate[A] {
      def run(t: MultiverseTransaction) = orBody
      def orelserun(t: MultiverseTransaction) = elseBody
    }.execute
  }
} 
/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable class Transaction extends Logging {
  private[this] var _id = 0L
  def id = _id
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New
  private[akka] var transaction: MultiverseTransaction = _

  private[this] var participants: List[String] = Nil
  private[this] var precommitted: List[String] = Nil

  private[this] val depth = new AtomicInteger(0)
  
  def increment = synchronized { depth.incrementAndGet }
  def decrement = synchronized { depth.decrementAndGet }
  def isTopLevel = synchronized { depth.get == 0 }
  
  def register(transactional: Transactional) = synchronized {
    ensureIsActiveOrNew
  }

  def begin(participant: String) = synchronized {
    ensureIsActiveOrNew
    transaction = Multiverse.STM.startUpdateTransaction("akka")
    _id = transaction.getReadVersion
    log.debug("Creating a new transaction with id [%s]", _id)

    if (status == TransactionStatus.New) log.debug("TX BEGIN - Server with UUID [%s] is starting NEW transaction [%s]", participant, toString)
    else log.debug("Server [%s] is participating in transaction", participant)
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
      log.debug("TX COMMIT - Committing transaction [%s] for server with UUID [%s]", toString, participant)
      val haveAllPreCommitted =
        if (participants.size == precommitted.size) {{
          for (part <- participants) yield {
            if (precommitted.exists(_ == part)) true
            else false
          }}.exists(_ == true)
        } else false
      if (haveAllPreCommitted && transaction != null) {
        transaction.commit
        status = TransactionStatus.Completed
        reset
        true
      } else false
    } else {
      reset
      true
    }
  }

  def rollback(participant: String) = synchronized {
    ensureIsActiveOrAborted
    log.debug("TX ROLLBACK - Server with UUID [%s] has initiated transaction rollback for [%s]", participant, toString)
    transaction.abort
    status = TransactionStatus.Aborted
    reset
  }

  def rollbackForRescheduling(participant: String) = synchronized {
    ensureIsActiveOrAborted
    log.debug("TX ROLLBACK for recheduling - Server with UUID [%s] has initiated transaction rollback for [%s]", participant, toString)
    transaction.abort
    reset
  }

  def join(participant: String) = synchronized {
    ensureIsActive
    log.debug("TX JOIN - Server with UUID [%s] is joining transaction [%s]" , participant, toString)
    participants ::= participant
  }

  def isNew = status == TransactionStatus.New
  def isActive = status == TransactionStatus.Active
  def isCompleted = status == TransactionStatus.Completed
  def isAborted = status == TransactionStatus.Aborted

  private def reset = {
    participants = Nil
    precommitted = Nil    
  }
  
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
      log.setLevel(Level.ALL)
    }
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null && 
    that.isInstanceOf[Transaction] && 
    that.asInstanceOf[Transaction].id == this.id
  }
 
  override def hashCode(): Int = id.toInt
 
  override def toString(): String = synchronized { 
    "Transaction[" + id + ", " + status + "]"
  }
}


@serializable sealed abstract class TransactionStatus
object TransactionStatus {
  case object New extends TransactionStatus
  case object Active extends TransactionStatus
  case object Aborted extends TransactionStatus
  case object Completed extends TransactionStatus
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
object TransactionIdFactory {
  // FIXME: will not work in distributed env
  private val currentId = new AtomicLong(0L)
  def newId = currentId.getAndIncrement
}
 */

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
@serializable class Transaction extends Logging {
  val id = TransactionIdFactory.newId

  log.debug("Creating a new transaction with id [%s]", id)

  @volatile private[this] var status: TransactionStatus = TransactionStatus.New

  private[this] val transactionalItems = new ChangeSet

  private[this] var participants: List[String] = Nil
  private[this] var precommitted: List[String] = Nil

  private[this] val depth = new AtomicInteger(0)
  
  def increment = synchronized { depth.incrementAndGet }
  def decrement = synchronized { depth.decrementAndGet }
  def isTopLevel = synchronized { depth.get == 0 }
  
  def register(transactional: Transactional) = synchronized {
    ensureIsActiveOrNew
    transactionalItems + transactional
  }

  def begin(participant: String) = synchronized {
    ensureIsActiveOrNew
    if (status == TransactionStatus.New) log.debug("TX BEGIN - Server with UUID [%s] is starting NEW transaction [%s]", participant, toString)
    else log.debug("Server [%s] is participating in transaction", participant)
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
      log.debug("TX COMMIT - Committing transaction [%s] for server with UUID [%s]", toString, participant)
      val haveAllPreCommitted =
        if (participants.size == precommitted.size) {{
          for (part <- participants) yield {
            if (precommitted.exists(_ == part)) true
            else false
          }}.exists(_ == true)
        } else false
      if (haveAllPreCommitted) {
        transactionalItems.items.foreach(_.commit)
        status = TransactionStatus.Completed
        reset
        true
      } else false
    } else {
      reset
      true
    }
  }

  def rollback(participant: String) = synchronized {
    ensureIsActiveOrAborted
    log.debug("TX ROLLBACK - Server with UUID [%s] has initiated transaction rollback for [%s]", participant, toString)
    transactionalItems.items.foreach(_.rollback)
    status = TransactionStatus.Aborted
    reset
  }

  def rollbackForRescheduling(participant: String) = synchronized {
    ensureIsActiveOrAborted
    log.debug("TX ROLLBACK for recheduling - Server with UUID [%s] has initiated transaction rollback for [%s]", participant, toString)
    transactionalItems.items.foreach(_.rollback)
    reset
  }

  def join(participant: String) = synchronized {
    ensureIsActive
    log.debug("TX JOIN - Server with UUID [%s] is joining transaction [%s]" , participant, toString)
    participants ::= participant
  }

  def isNew = status == TransactionStatus.New
  def isActive = status == TransactionStatus.Active
  def isCompleted = status == TransactionStatus.Completed
  def isAborted = status == TransactionStatus.Aborted

  private def reset = {
    transactionalItems.clear
    participants = Nil
    precommitted = Nil    
  }
  
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
      log.setLevel(Level.ALL)
    }
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null && 
    that.isInstanceOf[Transaction] && 
    that.asInstanceOf[Transaction].id == this.id
  }
 
  override def hashCode(): Int = id.toInt
 
  override def toString(): String = synchronized { 
    "Transaction[" + id + ", " + status + "]"
  }
}
 */
