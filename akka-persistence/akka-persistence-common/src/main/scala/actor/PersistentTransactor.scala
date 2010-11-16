package akka.persistence.common.actor

import akka.transactor.{Transactor}
import akka.actor.{FSM, ActorRef, Actor}
import org.multiverse.api.exceptions.TooManyRetriesException
import akka.persistence.common._
import akka.stm.{Recoverable, TransactionalVector}
import scala.collection.mutable.HashMap
import java.io.File
import org.fusesource.hawtdb.api._
import org.fusesource.hawtbuf.codec.{BytesCodec, StringCodec}

trait PersistentTransactor extends Transactor with Storage {

  type ElementType = Array[Byte]

  val refs = new HashMap[String, PersistentRef[ElementType]]
  val maps = new HashMap[String, PersistentMap[ElementType,ElementType]]
  val queues = new HashMap[String, PersistentQueue[ElementType]]
  val vectors = new HashMap[String, PersistentVector[ElementType]]
  val sortedSets = new HashMap[String, PersistentSortedSet[ElementType]]

  def recoveryManager: ActorRef

  final def atomically = {
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
    refs.clear
  }

  def logMaps() = {
    maps.values foreach {
      map => {
        if (map.appendOnlyTxLog.length > 0) {
          recoveryManager ! MapFailure(map.uuid, map.getLog)
        }
      }
    }
    maps.clear
  }

  def logVectors() = {
    vectors.values forech {
      vec => {
        if (vec.appendOnlyTxLog.length > 0) {
          recoveryManager ! VectorFailure(vec.uuid, vec.getLog)
        }
      }
    }
    vectors.clear
  }

  def logQueues() = {
    queues.values foreach {
      queue => {
        if (queue.appndenOnlyTxLog.length > 0) {
          recoveryManager ! QueueFailure(queue.uuid, queue.getLog)
        }
      }
    }
    queues.clear
  }

  def newRef(id: String) = {
    refs.getOrElseUpdate(id, {
      val ref = underlyingStorage.newRef(id)
      recoveryManager !! RefRecoveryRequest(id) match {
        case RefFailure(id, log) => ref.applyLog(log)
        case _ => ()
      }
    })
  }

  def newVector(id: String) = {
    vectors.getOrElseUpdate(id, {
      val vec = underlyingStorage.newVector(id)
      recoveryManager !! VectorRecoveryRequest(id) match {
        case VectorFailure(id, log) => vec.applyLog(log)
        case _ => ()
      }
    })
  }

  def newMap(id: String) = {
    maps.getOrElseUpdate(id, {
      val map = underlyingStorage.newMap(id)
      recoveryManager !! MapRecoveryRequest(id) match {
        case MapFailure(id, log) => map.applyLog(log)
        case _ => ()
      }
    })
  }

  def getRef(id: String) = {
    refs.getOrElseUpdate(id, {
      val ref = underlyingStorage.getRef(id)
      recoveryManager !! RefRecoveryRequest(id) match {
        case RefFailure(id, log) => ref.applyLog(log)
        case _ => ()
      }
    })
  }

  def getVector(id: String) = {
    vectors.getOrElseUpdate(id, {
      val vec = underlyingStorage.getVector(id)
      recoveryManager !! VectorRecoveryRequest(id) match {
        case VectorFailure(id, log) => vec.applyLog(log)
        case _ => ()
      }
    })
  }

  def getMap(id: String) = {
    maps.getOrElseUpdate(id, {
      val map = underlyingStorage.getMap(id)
      recoveryManager !! MapRecoveryRequest(id) match {
        case MapFailure(id, log) => map.applyLog(log)
        case _ => ()
      }
    })
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
    queues.getOrElseUpdate(id, {
      val queue = underlyingStorage.getQueue(id)
      recoveryManager !! QueueRecoveryRequest(id) match {
        case QueueFailure(id, log) => queue.applyLog(log)
        case _ => ()
      }
    })
  }

  override def getSortedSet(id: String) = {
    sortedSets.getOrElseUpdate(id, {
      val set = underlyingStorage.getSortedSet(id)
      recoveryManager !! SortedSetRecoveryRequest(id) match {
        case SortedSetFailure(id, log) => set.applyLog(log)
        case _ => ()
      }
    })
  }

  override def newQueue(id: String) = {
    queues.getOrElseUpdate(id, {
      val queue = underlyingStorage.newQueue(id)
      recoveryManager !! QueueRecoveryRequest(id) match {
        case QueueFailure(id, log) => queue.applyLog(log)
        case _ => ()
      }
    })
  }

  override def newSortedSet(id: String) = {
    sortedSets.getOrElseUpdate(id, {
      val set = underlyingStorage.newSortedSet(id)
      recoveryManager !! SortedSetRecoveryRequest(id) match {
        case SortedSetFailure(id, log) => set.applyLog(log)
        case _ => ()
      }
    })
  }
}


sealed trait RecoveryRequest

case class RefRecoveryRequest(uuid: String) extends RecoveryRequest

case class MapRecoveryRequest(uuid: String) extends RecoveryRequest

case class QueueRecoveryRequest(uuid: String) extends RecoveryRequest

case class VectorRecoveryRequest(uuid: String) extends RecoveryRequest

case class SortedSetRecoveryRequest(uuid: String) extends RecoveryRequest


@serializable
sealed trait CommitFailure

case class RefFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class MapFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class QueueFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class VectorFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class SortedSetFailure(uuid: String, tlog: Array[Byte]) extends CommitFailure

case class NoOp() extends CommitFailure


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
    case RefFailure(uuid, log) => {
      refFailed(uuid, log)
    }

    case RefRecoveryRequest(uuid) => {
      recoverRef(uuid) match {
        case None => self.reply(NoOp)
        case Some(tlog) => self.reply(RefFailure(uuid, tlog))
      }
    }

    case MapFailure(uuid, tlog) => {
      mapFailed(uuid, tlog)
    }

    case MapRecoveryRequest(uuid) => {
      recoverMap(uuid) match {
        case None => self.reply(NoOp)
        case Some(tlog) => self.reply(MapFailure(uuid, tlog))
      }
    }

    case QueueFailure(uuid, log) => {
      queueFailed(uuid, log)
    }

    case QueueRecoveryRequest(uuid) => {
      recoverRef(uuid) match {
        case None => self.reply(NoOp)
        case Some(tlog) => self.reply(RefFailure(uuid, tlog))
      }
    }

    case VectorFailure(uuid, tlog) => {
      vectorFailed(uuid, tlog)
    }

    case VectorRecoveryRequest(uuid) => {
      recoverVector(uuid) match {
        case None => self.reply(NoOp)
        case Some(tlog) => self.reply(VectorFailure(uuid, tlog))
      }
    }

    case SortedSetFailure(uuid, log) => {
      sortedSetFailed(uuid, log)
    }

    case SortedSetRecoveryRequest(uuid) => {
      recoverSortedSet(uuid) match {
        case None => self.reply(NoOp)
        case Some(tlog) => self.reply(SortedSetFailure(uuid, tlog))
      }
    }

  }

  def refFailed(uuid: String, tlog: Array[Byte]): Unit

  def recoverRef(uuid: String): Option[Array[Byte]]

  def mapFailed(uuid: String, tlog: Array[Byte]): Unit

  def recoverMap(uuid: String): Option[Array[Byte]]

  def queueFailed(uuid: String, tlog: Array[Byte]): Unit

  def recoverQueue(uuid: String): Option[Array[Byte]]

  def vectorFailed(uuid: String, tlog: Array[Byte]): Unit

  def recoverVector(uuid: String): Option[Array[Byte]]

  def sortedSetFailed(uuid: String, tlog: Array[Byte]): Unit

  def recoverSortedSet(uuid: String): Option[Array[Byte]]

}

class HawtDbRecoveryManager(dir: String) extends PersistentTransactorRecoveryManager {
  var refFactory: TxPageFileFactory = null
  var refPageFile: TxPageFile = null
  var refIndexFactory: HashIndexFactory[String, Array[Byte]] = null
  var refIndex: Index[String, Array[Byte]] = null
  var mapFactory: TxPageFileFactory = null
  var mapPageFile: TxPageFile = null
  var mapIndexFactory: HashIndexFactory[String, Array[Byte]] = null
  var mapIndex: Index[String, Array[Byte]] = null
  var vectorFactory: TxPageFileFactory = null
  var vectorPageFile: TxPageFile = null
  var vectorIndexFactory: HashIndexFactory[String, Array[Byte]] = null
  var vectorIndex: Index[String, Array[Byte]] = null
  var queueFactory: TxPageFileFactory = null
  var queuePageFile: TxPageFile = null
  var queueIndexFactory: HashIndexFactory[String, Array[Byte]] = null
  var queueIndex: Index[String, Array[Byte]] = null
  var sortedSetFactory: TxPageFileFactory = null
  var sortedSetPageFile: TxPageFile = null
  var sortedSetIndexFactory: HashIndexFactory[String, Array[Byte]] = null
  var sortedSetIndex: Index[String, Array[Byte]] = null


  override def preStart = {
    val datadir = new File(dir)
    if (!datadir.exists || !datadir.isDirectory) {
      throw new IllegalArgumentException(datadir.getAbsolutePath, "does not exist or is not a directory")
    }
    val (refFactory, refPageFile, refIndexFactory, refIndex) = initType(new File(dir, "ref.log"))
    val (mapFactory, mapPageFile, mapIndexFactory, mapIndex) = initType(new File(dir, "map.log"))
    val (vectorFactory, vectorPageFile, vectorIndexFactory, vectorIndex) = initType(new File(dir, "vector.log"))
    val (queueFactory, queuePageFile, queueIndexFactory, queueIndex) = initType(new File(dir, "queue.log"))
    val (sortedSetFactory, sortedSetPageFile, sortedSetIndexFactory, sortedSetIndex) = initType(new File(dir, "sortedset.log"))
  }

  def initType(file: File): (TxPageFileFactory, TxPageFile, IndexFactory, Index) = {
    val factory = new TxPageFileFactory()
    factory.setFile(file)
    factory.open
    val txFile = factory.getTxPageFile
    val indexFact = new HashIndexFactory[String, Array[Byte]]
    indexFact.setKeyCodec(StringCodec.INSTANCE)
    indexFact.setValueCodec(BytesCodec.INSTANCE)
    if (!txFile.allocator.isAllocated(0)) {
      val tx = txFile.tx();
      Index[String, Array[Byte]] root = indexFact.create(tx);
      tx.commit();
    }
  }


  def withIndex(txp: TxPageFile, idxf: IndexFactory[String, Array[Byte]])(block: Index => Any) {
    val tx = txp.tx
    val idx = idxf.open(tx)
    block(index)
    tx.commit
  }

  override def postStop = {

  }

  def recoverSortedSet(uuid: String) = null

  def sortedSetFailed(uuid: String, tlog: Array[Byte]) = null

  def recoverVector(uuid: String) = null

  def vectorFailed(uuid: String, tlog: Array[Byte]) = null

  def recoverQueue(uuid: String) = null

  def queueFailed(uuid: String, tlog: Array[Byte]) = null

  def recoverMap(uuid: String) = null

  def mapFailed(uuid: String, tlog: Array[Byte]) = null

  def recoverRef(uuid: String) =  null

  def refFailed(uuid: String, tlog: Array[Byte]) = withIndex(refPageFile, refIndexFactory)(_.put(uuid, tlog))


}