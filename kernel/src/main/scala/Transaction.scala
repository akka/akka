/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.HashMap

sealed abstract case class TransactionStatus
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
  private val currentId = new AtomicLong(0L)
  def newId = currentId.getAndIncrement
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Transaction extends Logging {
  val id = TransactionIdFactory.newId
  
  private[this] var parent: Option[Transaction] = None
  private[this] var oldActorVersions = new HashMap[GenericServerContainer, GenericServer]
  private[this] var precommitted: List[GenericServerContainer] = Nil
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New

  def begin(server: GenericServerContainer) = synchronized {
    if (status == TransactionStatus.Aborted) throw new IllegalStateException("Can't begin ABORTED transaction")
    if (status == TransactionStatus.Completed) throw new IllegalStateException("Can't begin COMPLETED transaction")
    if (status == TransactionStatus.New) log.debug("Actor [%s] is starting NEW transaction", server)
    else log.debug("Actor [%s] is participating in transaction", server)
    val oldVersion = server.cloneServerAndReturnOldVersion
    oldActorVersions.put(server, oldVersion)
    status = TransactionStatus.Active
  }

  def precommit(server: GenericServerContainer) = synchronized {
    ensureIsActive
    log.debug("Pre-committing transaction for actor [%s]", server)
    precommitted ::= server
  }

  def commit(server: GenericServerContainer) = synchronized {
    ensureIsActive
    log.debug("Committing transaction for actor [%s]", server)
    val haveAllPreCommitted =
      if (oldActorVersions.size == precommitted.size) {{
        for (server <- oldActorVersions.keys) yield {
          if (precommitted.exists(_.id == server.id)) true
          else false
        }}.exists(_ == false)
      } else false
      
    if (haveAllPreCommitted) status = TransactionStatus.Completed
    else rollback(server)
  }

  def rollback(server: GenericServerContainer) = synchronized {
    ensureIsActive
    log.debug("Actor [%s] has initiated transaction rollback, rolling back [%s]" , server, oldActorVersions.keys)
    oldActorVersions.foreach(entry => {
        val (server, backup) = entry
        server.swapServer(backup)
    })
    status = TransactionStatus.Aborted
  }

  private def ensureIsActive = if (status == TransactionStatus.Active) 
    throw new IllegalStateException("Expected ACTIVE transaction - current status [" + status + "]")

  override def equals(that: Any): Boolean = 
    that != null && 
    that.isInstanceOf[Transaction] && 
    that.asInstanceOf[Transaction].id == this.id
 
  override def hashCode(): Int = id.toInt
 
  override def toString(): String = "Transaction[" + id + ", " + status + "]"
}
