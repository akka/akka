package akka.persistence.common.actor

import akka.transactor.{Transactor}
import akka.actor.{FSM, ActorRef, Actor}
import org.multiverse.api.exceptions.TooManyRetriesException
import akka.persistence.common._
import akka.stm.{Recoverable, TransactionalVector}
import scala.collection.mutable.HashMap

import akka.util.Logging


trait PersistentTransactor extends Transactor with Storage {

  type ElementType

  val refs = new HashMap[String, PersistentRef[ElementType]]
  val maps = new HashMap[String, PersistentMap[ElementType]]
  val queues = new HashMap[String, PersistentQueue[ElementType]]
  val vectors = new HashMap[String, PersistentVector[ElementType]]
  val sortedSets = new HashMap[String, PersistentSortedSet[ElementType]]

  def recoveryManager: ActorRef

  def atomically = {
    try
    {
      atomicallyWithStorage
    } catch {
      case e: TooManyRetriesException => {
        log.error(e, "Caught Too Many Retries Exception, sending uncommitted state to recoveryManager")
        logRefs
        logMaps
        logQueues
        logVectors
        logSets
        throw e
      }
    }
  }


  def atomicallyWithStorage: Receive

  def underlyingStorage: Storage

  def logRefs() = {
    refs.values foreach {
      ref => {
        if (ref.ref.isDefined) {
          recoveryManager ! RefFailure(ref.uuid, ref.getLog)
        }
      }
    }
  }

  def logMaps() = {
    maps.values foreach {
      map => {
        if (map.appendOnlyTxLog.length > 0) {
          recoveryManager ! MapFailure(map.uuid, map.getLog)
        }
      }
    }
  }

  def logVectors() = {
    vectors.values forech {
      vec => {
        if (vec.appendOnlyTxLog.length > 0) {
          recoveryManager ! VectorFailure(vec.uuid, vec.getLog)
        }
      }
    }
  }

  def logQueues() = {
    queues.values foreach {
      queue => {
        if (queue.appndenOnlyTxLog.length > 0) {
          recoveryManager ! QueueFailure(queue.uuid, queue.getLog)
        }
      }
    }
  }

  def newRef(id: String) = {
    val ref = underlyingStorage.newRef(id)
    refs.put(ref.uuid, ref)
    ref
  }

  def newVector(id: String) = {
    val vec = underlyingStorage.newVector(id)
    vectors.put(vec.uuid, vec)
    vec
  }

  def newMap(id: String) = {
    val map = underlyingStorage.newMap(id)
    maps.put(map.uuid, map)
    map
  }

  def getRef(id: String) = {
    refs.getOrElseUpdate(id, {
      val ref = underlyingStorage.getRef(id)
      recoveryManager !! RefRecovery(id) match {
        case RefFailure(id, log) => ref.applyLog(log)
        case _ => ()
      }
    })
  }

  def getVector(id: String) = {
    val vec = underlyingStorage.getVector(id)
    vectors.put(vec.uuid, vec)
    vec
  }

  def getMap(id: String) = {
    val map = underlyingStorage.newMap(id)
    maps.put(map.uuid, map)
    map
  }

  def newRef = {
    val ref = underlyingStorage.newRef
    refs.put(ref.uuid, ref)
    ref
  }

  def newVector = {
    val vec = underlyingStorage.newVector
    vectors.put(vec.uuid, vec)
    vec
  }

  def newMap = {
    val map = underlyingStorage.newMap
    maps.put(map.uuid, map)
    map
  }

  override def transactional = underlyingStorage.transactional

  override def newQueue = {
    val queue = underlyingStorage.newQueue
    queues.put(queue.uuid, queue)
    queue
  }

  override def newSortedSet = {
    val set = underlyingStorage.newSortedSet
    sortedSets.put(set.uuid, set)
    set
  }

  override def getQueue(id: String) = {
    val queue = underlyingStorage.getQueue(id)
    queues.put(queue.uuid, queue)
    queue
  }

  override def getSortedSet(id: String) = {
    val set = underlyingStorage.getSortedSet(id)
    sortedSets.put(set.uuid, set)
    set
  }

  override def newQueue(id: String) = {
    val queue = underlyingStorage.newQueue(id)
    queues.put(queue.uuid, queue)
    queue
  }

  override def newSortedSet(id: String) = {
    val set = underlyingStorage.newSortedSet(id)
    sortedSets.put(set.uuid, set)
    set
  }
}


sealed trait CommitRecovery

case class RefRecovery(uuid: String) extends CommitRecovery

case class MapRecovery(uuid: String) extends CommitRecovery

case class QueueRecovery(uuid: String) extends CommitRecovery

case class VectorRecovery(uuid: String) extends CommitRecovery

case class SortedSetRecovery(uuid: String) extends CommitRecovery


@serializable
sealed trait CommitFailure

case class RefFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class MapFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class QueueFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class VectorFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class SortedSetFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure


trait PersistentTransactorSupervisor extends Actor {
  //send messages to this to start PersistentTransactors,
  // this will start up a recovery manager and wire up the recoverManager ref into PersistentTransactors
}

sealed trait RecoveryMesage

case class RecoverFailedTransaction() extends RecoveryMesage

trait PersistentTransactorRecoveryManager extends Actor {
  //log the failed transactions in hawtdb
  //send the failed transactions back to the restarted actor

  protected def receive = {
    case RefFailure(uuid, tlog, error) => {
      log.info(uuid + " " + error)
    }
  }


}