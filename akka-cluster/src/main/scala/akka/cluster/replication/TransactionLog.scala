/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import org.apache.bookkeeper.client.{BookKeeper, LedgerHandle, LedgerEntry, BKException, AsyncCallback}
import org.apache.zookeeper.CreateMode

import org.I0Itec.zkclient.exception._

import akka.config._
import Config._
import akka.util._
import akka.event.EventHandler
import akka.dispatch.{DefaultCompletableFuture, CompletableFuture}
import akka.AkkaException

import akka.cluster.zookeeper._

import java.util.Enumeration

import scala.collection.JavaConversions._

// FIXME allow user to choose dynamically between 'async' and 'sync' tx logging (asyncAddEntry(byte[] data, AddCallback cb, Object ctx))
// FIXME clean up old entries in log after doing a snapshot
// FIXME clean up all meta-data in ZK for a specific UUID when the corresponding actor is shut down
// FIXME delete tx log after migration of actor has been made and create a new one

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ReplicationException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionLog private (
  ledger: LedgerHandle, val id: String, val isAsync: Boolean) {
  import TransactionLog._

  val logId        = ledger.getId
  val txLogPath    = transactionLogNode + "/" + id
  val snapshotPath = txLogPath + "/snapshot"

  private val isOpen = new Switch(true)

  /**
   * TODO document method
   */
  def recordEntry(entry: Array[Byte]): Unit = if (isOpen.isOn) {
    try {
      if (isAsync) {
        ledger.asyncAddEntry(
          entry,
          new AsyncCallback.AddCallback {
            def addComplete(
              returnCode: Int,
              ledgerHandle: LedgerHandle,
              entryId: Long,
              ctx: AnyRef) {
              handleReturnCode(returnCode)
              EventHandler.debug(this,
                "Writing entry [%s] to log [%s]".format(entryId, logId))
            }
          },
          null)
      } else {
        handleReturnCode(ledger.addEntry(entry))
        val entryId = ledger.getLastAddPushed
        EventHandler.debug(this, "Writing entry [%s] to log [%s]".format(entryId, logId))
      }
    } catch {
      case e => handleError(e)
    }
  } else transactionClosedError

  /**
   * TODO document method
   */
  def recordSnapshot(snapshot: Array[Byte]): Unit = if (isOpen.isOn) {
    try {
      if (isAsync) {
        ledger.asyncAddEntry(
          snapshot,
          new AsyncCallback.AddCallback {
            def addComplete(
              returnCode: Int,
              ledgerHandle: LedgerHandle,
              entryId: Long,
              ctx: AnyRef) {
              handleReturnCode(returnCode)
              storeSnapshotMetaDataInZooKeeper(entryId)
            }
          },
          null)
      } else {
        handleReturnCode(ledger.addEntry(snapshot))
        storeSnapshotMetaDataInZooKeeper(ledger.getLastAddPushed)
      }
    } catch {
      case e => handleError(e)
    }
  } else transactionClosedError

  /**
   * TODO document method
   */
  def entries: Vector[Array[Byte]] = entriesInRange(0, ledger.getLastAddConfirmed)

  /**
   * TODO document method
   */
  def entriesFromLatestSnapshot: Tuple2[Array[Byte], Vector[Array[Byte]]] = {
    val snapshotId = latestSnapshotId
    EventHandler.debug(this,
      "Reading entries from snapshot id [%s] for log [%s]".format(snapshotId, logId))
    (entriesInRange(snapshotId, snapshotId).head, entriesInRange(snapshotId + 1, ledger.getLastAddConfirmed))
  }

  /**
   * TODO document method
   */
  def entriesInRange(from: Long, to: Long): Vector[Array[Byte]] = if (isOpen.isOn) {
    try {
      if (from < 0)  throw new IllegalArgumentException("'from' can't be negative [" + from + "]")
      if (to < 0)    throw new IllegalArgumentException("'to' can't be negative [" + from + "]")
      if (to < from) throw new IllegalArgumentException("'to' can't be smaller than 'from' [" + from + "," + to + "]")
      EventHandler.debug(this,
        "Reading entries [%s -> %s] for log [%s]".format(from, to, logId))
      if (isAsync) {
        val future = new DefaultCompletableFuture[Vector[Array[Byte]]](timeout)
        ledger.asyncReadEntries(
          from, to,
          new AsyncCallback.ReadCallback {
            def readComplete(
              returnCode: Int,
              ledgerHandle: LedgerHandle,
              enumeration: Enumeration[LedgerEntry],
              ctx: AnyRef) {
              val future = ctx.asInstanceOf[CompletableFuture[Vector[Array[Byte]]]]
              var entries = Vector[Array[Byte]]()
              while (enumeration.hasMoreElements) {
                entries = entries :+ enumeration.nextElement.getEntry
              }
              if (returnCode == BKException.Code.OK) future.completeWithResult(entries)
              else future.completeWithException(BKException.create(returnCode))
            }
          },
          future)
        await(future)
      } else {
        val enumeration = ledger.readEntries(from, to)
        var entries = Vector[Array[Byte]]()
        while (enumeration.hasMoreElements) {
          entries = entries :+ enumeration.nextElement.getEntry
        }
        entries
      }
    } catch {
      case e => handleError(e)
    }
  } else transactionClosedError

  /**
   * TODO document method
   */
  def latestEntryId: Long = ledger.getLastAddConfirmed

  /**
   * TODO document method
   */
  def latestSnapshotId: Long = {
    try {
      val snapshotId = zkClient.readData(snapshotPath).asInstanceOf[Long]
      EventHandler.debug(this,
        "Retrieved latest snapshot id [%s] from transaction log [%s]".format(snapshotId, logId))
      snapshotId
    } catch {
      case e: ZkNoNodeException =>
        handleError(new ReplicationException(
          "Transaction log for UUID [" + id +
          "] does not have a snapshot recorded in ZooKeeper"))
      case e => handleError(e)
    }
  }

  /**
   * TODO document method
   */
  def delete(): Unit = if (isOpen.isOn) {
    EventHandler.debug(this, "Deleting transaction log [%s]".format(logId))
    try {
      if (isAsync) {
        bookieClient.asyncDeleteLedger(
          logId,
          new AsyncCallback.DeleteCallback {
            def deleteComplete(returnCode: Int, ctx: AnyRef) {
              handleReturnCode(returnCode)
            }
          },
          null)
      } else {
        bookieClient.deleteLedger(logId)
      }
    } catch {
      case e => handleError(e)
    }
  }

  /**
   * TODO document method
   */
  def close(): Unit = if (isOpen.switchOff) {
    EventHandler.debug(this, "Closing transaction log [%s]".format(logId))
    try {
      if (isAsync) {
        ledger.asyncClose(
          new AsyncCallback.CloseCallback {
            def closeComplete(
              returnCode: Int,
              ledgerHandle: LedgerHandle,
              ctx: AnyRef) {
              handleReturnCode(returnCode)
            }
          },
          null)
      } else {
        ledger.close
      }
    } catch {
      case e => handleError(e)
    }
  }

  private def storeSnapshotMetaDataInZooKeeper(snapshotId: Long): Unit = if (isOpen.isOn) {
    try {
      zkClient.create(snapshotPath, null, CreateMode.PERSISTENT)
    } catch {
      case e: ZkNodeExistsException => {} // do nothing
      case e                        => handleError(e)
    }

    try {
      zkClient.writeData(snapshotPath, snapshotId)
    } catch {
      case e =>
        handleError(new ReplicationException(
          "Could not store transaction log snapshot meta-data in ZooKeeper for UUID [" +
          id +"]"))
    }
    EventHandler.debug(this,
      "Writing snapshot [%s] to log [%s]".format(snapshotId, logId))
  } else transactionClosedError

  private def handleReturnCode(block: => Long) {
    val code = block.toInt
    if (code == BKException.Code.OK) {} // all fine
    else handleError(BKException.create(code))
  }

  private def transactionClosedError: Nothing = {
    handleError(new ReplicationException(
      "Transaction log [" + logId +
      "] is closed. You need to open up new a new one with 'TransactionLog.logFor(id)'"))
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionLog {

  val digestType = config.getString("akka.cloud.cluster.replication.digest-type", "CRC32") match {
    case "CRC32" => BookKeeper.DigestType.CRC32
    case "MAC"   => BookKeeper.DigestType.MAC
    case unknown => throw new ConfigurationException(
                    "akka.cloud.cluster.replication.digest-type is invalid [" + unknown + "]")
  }
  val password     = config.getString("akka.cloud.cluster.replication.password", "secret").getBytes("UTF-8")
  val ensembleSize = config.getInt("akka.cloud.cluster.replication.ensemble-size", 3)
  val quorumSize   = config.getInt("akka.cloud.cluster.replication.quorum-size", 2)
  val timeout      = 5000 // FIXME make configurable

  private[akka] val transactionLogNode = "/transaction-log-ids"

  private val isConnected = new Switch(false)

  private[akka] lazy val (bookieClient, zkClient) = {
    val bk = new BookKeeper(Cluster.zooKeeperServers)

    val zk = new AkkaZkClient(
      Cluster.zooKeeperServers,
      Cluster.sessionTimeout,
      Cluster.connectionTimeout,
      Cluster.defaultSerializer)

    try {
      zk.create(transactionLogNode, null, CreateMode.PERSISTENT)
    } catch {
      case e: ZkNodeExistsException => {} // do nothing
      case e                        => handleError(e)
    }

    EventHandler.info(this,
      ("Transaction log service started with" +
      "\n\tdigest type [%s]" +
      "\n\tensemble size [%s]" +
      "\n\tquorum size [%s]" +
      "\n\tlogging time out [%s]").format(
        digestType,
        ensembleSize,
        quorumSize,
        timeout))
    isConnected.switchOn
    (bk, zk)
  }

  private[akka] def apply(ledger: LedgerHandle, id: String, isAsync: Boolean = false) =
    new TransactionLog(ledger, id, isAsync)

  /**
   * TODO document method
   */
  def shutdown() {
    isConnected switchOff {
      try {
        zkClient.close
        bookieClient.halt
      } catch {
        case e => handleError(e)
      }
    }
  }

  /**
   * TODO document method
   */
  def newLogFor(id: String, isAsync: Boolean = false): TransactionLog = {
    val txLogPath = transactionLogNode + "/" + id

    val ledger = try {
      if (zkClient.exists(txLogPath)) throw new ReplicationException(
          "Transaction log for UUID [" + id +"] already exists")

      val future = new DefaultCompletableFuture[LedgerHandle](timeout)
      if (isAsync) {
        bookieClient.asyncCreateLedger(
          ensembleSize, quorumSize, digestType, password,
          new AsyncCallback.CreateCallback {
            def createComplete(
              returnCode: Int,
              ledgerHandle: LedgerHandle,
              ctx: AnyRef) {
              val future = ctx.asInstanceOf[CompletableFuture[LedgerHandle]]
              if (returnCode == BKException.Code.OK) future.completeWithResult(ledgerHandle)
              else future.completeWithException(BKException.create(returnCode))
            }
          },
          future)
        await(future)
      } else {
        bookieClient.createLedger(ensembleSize, quorumSize, digestType, password)
      }
    } catch {
      case e => handleError(e)
    }

    val logId = ledger.getId
    try {
      zkClient.create(txLogPath, null, CreateMode.PERSISTENT)
      zkClient.writeData(txLogPath, logId)
      logId
    } catch {
      case e =>
        bookieClient.deleteLedger(logId) // clean up
        handleError(new ReplicationException(
          "Could not store transaction log [" + logId +
          "] meta-data in ZooKeeper for UUID [" + id +"]"))
    }

    EventHandler.info(this,
      "Created new transaction log [%s] for UUID [%s]".format(logId, id))
    TransactionLog(ledger, id, isAsync)
  }

  /**
   * TODO document method
   */
  def logFor(id: String, isAsync: Boolean = false): TransactionLog = {
    val txLogPath = transactionLogNode + "/" + id

    val logId = try {
      val logId = zkClient.readData(txLogPath).asInstanceOf[Long]
      EventHandler.debug(this,
        "Retrieved transaction log [%s] for UUID [%s]".format(logId, id))
      logId
    } catch {
      case e: ZkNoNodeException =>
        handleError(new ReplicationException(
          "Transaction log for UUID [" + id +"] does not exist in ZooKeeper"))
      case e => handleError(e)
    }

    val ledger = try {
      if (isAsync) {
        val future = new DefaultCompletableFuture[LedgerHandle](timeout)
        bookieClient.asyncOpenLedger(
          logId, digestType, password,
          new AsyncCallback.OpenCallback {
            def openComplete(
              returnCode: Int,
              ledgerHandle: LedgerHandle,
              ctx: AnyRef) {
              val future = ctx.asInstanceOf[CompletableFuture[LedgerHandle]]
              if (returnCode == BKException.Code.OK) future.completeWithResult(ledgerHandle)
              else future.completeWithException(BKException.create(returnCode))
            }
          },
          future)
        await(future)
      } else {
        bookieClient.openLedger(logId, digestType, password)
      }
    } catch {
      case e => handleError(e)
    }

    TransactionLog(ledger, id, isAsync)
  }

  private[akka] def await[T](future: CompletableFuture[T]): T = {
    future.await
    if (future.result.isDefined)         future.result.get
    else if (future.exception.isDefined) handleError(future.exception.get)
    else handleError(new ReplicationException("No result from async read of entries for transaction log"))
  }

  private[akka] def handleError(e: Throwable): Nothing = {
    EventHandler.error(e, this, e.toString)
    throw e
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object LocalBookKeeperEnsemble {
  private val isRunning = new Switch(false)
  private val port = 5555

  @volatile private var localBookKeeper: LocalBookKeeper = _

  /**
   * TODO document method
   */
  def start() {
    isRunning switchOn {
      localBookKeeper = new LocalBookKeeper(TransactionLog.ensembleSize)
      localBookKeeper.runZookeeper(port)
      localBookKeeper.initializeZookeper
      localBookKeeper.runBookies
    }
  }

  /**
   * TODO document method
   */
  def shutdown() {
    isRunning switchOff {
      localBookKeeper.bs.foreach(_.shutdown) // stop bookies
      localBookKeeper.zkc.close              // stop zk client
      localBookKeeper.zks.shutdown           // stop zk server
      localBookKeeper.serverFactory.shutdown // stop zk NIOServer
    }
  }
}
