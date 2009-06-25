/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.stm

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import kernel.util.Logging
import scala.collection.mutable.{HashSet, HashMap}

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
 */
object TransactionIdFactory {
  // FIXME: will not work in distributed env
  private val currentId = new AtomicLong(0L)
  def newId = currentId.getAndIncrement
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable class Transaction extends Logging {
  val id = TransactionIdFactory.newId

  log.debug("Creating a new transaction with id [%s]", id)

  // FIXME: add support for nested transactions
  private[this] var parent: Option[Transaction] = None
  private[this] val participants = new HashSet[ChangeSet]
  private[this] val precommitted = new HashSet[ChangeSet]
  private[this] val depth = new AtomicInteger(0)
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New

  def increment = depth.incrementAndGet
  def decrement = depth.decrementAndGet
  def topLevel_? = depth.get == 0
  
  def begin(changeSet: ChangeSet) = synchronized {
    ensureIsActiveOrNew
    if (status == TransactionStatus.New) log.debug("TX BEGIN - Server [%s] is starting NEW transaction [%s]", changeSet.id, this)
    else log.debug("Server [%s] is participating in transaction", changeSet.id)
    changeSet.full.foreach(_.begin)
    participants + changeSet
    status = TransactionStatus.Active
  }

  def precommit(changeSet: ChangeSet) = synchronized {
    if (status == TransactionStatus.Active) {
      log.debug("TX PRECOMMIT - Pre-committing transaction [%s] for server [%s]", this, changeSet.id)
      precommitted + changeSet
    }
  }

  def commit(changeSet: ChangeSet) = synchronized {
    if (status == TransactionStatus.Active) {
      log.debug("TX COMMIT - Committing transaction [%s] for server [%s]", this, changeSet.id)
      val haveAllPreCommitted =
        if (participants.size == precommitted.size) {{
          for (server <- participants) yield {
            if (precommitted.exists(_.id == changeSet.id)) true
            else false
          }}.exists(_ == true)
        } else false
      if (haveAllPreCommitted) {
        participants.foreach(_.full.foreach(_.commit))
        status = TransactionStatus.Completed
      } else rollback(changeSet)
    }
    participants.clear
    precommitted.clear
  }

  def rollback(changeSet: ChangeSet) = synchronized {
    ensureIsActiveOrAborted
    log.debug("TX ROLLBACK - Server [%s] has initiated transaction rollback for [%s], rolling back [%s]", changeSet.id, this, participants)
    participants.foreach(_.full.foreach(_.rollback))
    status = TransactionStatus.Aborted
    participants.clear
    precommitted.clear
  }

  def join(changeSet: ChangeSet) = synchronized {
    ensureIsActive
    log.debug("TX JOIN - Server [%s] is joining transaction [%s]" , changeSet.id, this)
    changeSet.full.foreach(_.begin)
    participants + changeSet
  }

  def isNew = status == TransactionStatus.New
  def isActive = status == TransactionStatus.Active
  def isCompleted = status == TransactionStatus.Completed
  def isAborted = status == TransactionStatus.Aborted

  private def ensureIsActive = if (status != TransactionStatus.Active)
    throw new IllegalStateException("Expected ACTIVE transaction - current status [" + status + "]")

  private def ensureIsActiveOrAborted = if (!(status == TransactionStatus.Active || status == TransactionStatus.Aborted))
    throw new IllegalStateException("Expected ACTIVE or ABORTED transaction - current status [" + status + "]")

  private def ensureIsActiveOrNew = if (!(status == TransactionStatus.Active || status == TransactionStatus.New))
    throw new IllegalStateException("Expected ACTIVE or NEW transaction - current status [" + status + "]")

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