package akka.cluster

/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import org.apache.bookkeeper.client.{ BookKeeper, LedgerHandle, LedgerEntry, BKException, AsyncCallback }
import org.apache.zookeeper.CreateMode

import org.I0Itec.zkclient.exception._

import akka.AkkaException
import akka.config._
import Config._
import akka.util._
import akka.actor._
import DeploymentConfig.ReplicationScheme
import akka.event.EventHandler
import akka.dispatch.{ DefaultPromise, Promise, MessageInvocation }
import akka.cluster.zookeeper._
import akka.serialization.ActorSerialization._
import akka.serialization.Compression.LZF

import java.util.Enumeration

// FIXME allow user to choose dynamically between 'async' and 'sync' tx logging (asyncAddEntry(byte[] data, AddCallback cb, Object ctx))
// FIXME clean up old entries in log after doing a snapshot

class ReplicationException(message: String, cause: Throwable = null) extends AkkaException(message) {
  def this(msg: String) = this(msg, null)
}

/**
 * A TransactionLog makes chunks of data durable.
 */
class TransactionLog private (
  ledger: LedgerHandle,
  val id: String,
  val isAsync: Boolean,
  replicationScheme: ReplicationScheme) {

  import TransactionLog._

  val logId = ledger.getId
  val txLogPath = transactionLogPath(id)
  val snapshotPath = txLogPath + "/snapshot"

  private val isOpen = new Switch(true)

  /**
   * Record an Actor message invocation.
   *
   * @param invocation the MessageInvocation to record
   * @param actorRef the LocalActorRef that received the message.
   * @throws ReplicationException if the TransactionLog already is closed.
   */
  def recordEntry(invocation: MessageInvocation, actorRef: LocalActorRef) {
    val entryId = ledger.getLastAddPushed + 1
    val needsSnapshot = entryId != 0 && (entryId % snapshotFrequency) == 0

    if (needsSnapshot) {
      //todo: could it be that the message is never persisted when a snapshot is added?
      val bytes = toBinary(actorRef, false, replicationScheme)
      recordSnapshot(bytes)
    } else {
      val bytes = MessageSerializer.serialize(invocation.message.asInstanceOf[AnyRef]).toByteArray
      recordEntry(bytes)
    }
  }

  /**
   * Record an entry.
   *
   * @param entry the entry in byte form to record.
   * @throws ReplicationException if the TransactionLog already is closed.
   */
  def recordEntry(entry: Array[Byte]) {
    if (isOpen.isOn) {
      val entryBytes =
        if (shouldCompressData) LZF.compress(entry)
        else entry

      try {
        if (isAsync) {
          ledger.asyncAddEntry(
            entryBytes,
            new AsyncCallback.AddCallback {
              def addComplete(returnCode: Int, ledgerHandle: LedgerHandle, entryId: Long, ctx: AnyRef) {
                handleReturnCode(returnCode)
                EventHandler.debug(this, "Writing entry [%s] to log [%s]".format(entryId, logId))
              }
            },
            null)
        } else {
          handleReturnCode(ledger.addEntry(entryBytes))
          val entryId = ledger.getLastAddPushed
          EventHandler.debug(this, "Writing entry [%s] to log [%s]".format(entryId, logId))
        }
      } catch {
        case e: Throwable ⇒ handleError(e)
      }
    } else transactionClosedError
  }

  /**
   * Record a snapshot.
   *
   * @param snapshot the snapshot in byteform to record.
   * @throws ReplicationException if the TransactionLog already is closed.
   */
  def recordSnapshot(snapshot: Array[Byte]) {
    if (isOpen.isOn) {
      val snapshotBytes =
        if (shouldCompressData) LZF.compress(snapshot)
        else snapshot

      try {
        if (isAsync) {
          ledger.asyncAddEntry(
            snapshotBytes,
            new AsyncCallback.AddCallback {
              def addComplete(returnCode: Int, ledgerHandle: LedgerHandle, snapshotId: Long, ctx: AnyRef) {
                handleReturnCode(returnCode)
                EventHandler.debug(this, "Writing snapshot to log [%s]".format(snapshotId))
                storeSnapshotMetaDataInZooKeeper(snapshotId)
              }
            },
            null)
        } else {
          //todo: could this be racy, since writing the snapshot itself and storing the snapsnot id, is not
          //an atomic operation?

          //first store the snapshot.
          handleReturnCode(ledger.addEntry(snapshotBytes))
          val snapshotId = ledger.getLastAddPushed

          //this is the location where all previous entries can be removed.
          //TODO: how to remove data?

          EventHandler.debug(this, "Writing snapshot to log [%s]".format(snapshotId))
          //and now store the snapshot metadata.
          storeSnapshotMetaDataInZooKeeper(snapshotId)
        }
      } catch {
        case e: Throwable ⇒ handleError(e)
      }
    } else transactionClosedError
  }

  /**
   * Get all the entries for this transaction log.
   *
   * @throws ReplicationException if the TransactionLog already is closed.
   */
  def entries: Vector[Array[Byte]] = entriesInRange(0, ledger.getLastAddConfirmed)

  /**
   * Get the latest snapshot and all subsequent entries from this snapshot.
   */
  def latestSnapshotAndSubsequentEntries: (Option[Array[Byte]], Vector[Array[Byte]]) = {
    latestSnapshotId match {
      case Some(snapshotId) ⇒
        EventHandler.debug(this, "Reading entries from snapshot id [%s] for log [%s]".format(snapshotId, logId))

        val cursor = snapshotId + 1
        val lastIndex = ledger.getLastAddConfirmed

        val snapshot = Some(entriesInRange(snapshotId, snapshotId).head)

        val entries =
          if ((cursor - lastIndex) == 0) Vector.empty[Array[Byte]]
          else entriesInRange(cursor, lastIndex)

        (snapshot, entries)

      case None ⇒
        (None, entries)
    }
  }

  /**
   * Get a range of entries from 'from' to 'to' for this transaction log.
   *
   * @param from the first element of the range
   * @param the last index from the range (including).
   * @return a Vector containing Byte Arrays. Each element in the vector is a record.
   * @throws IllegalArgumenException if from or to is negative, or if 'from' is bigger than 'to'.
   * @throws ReplicationException if the TransactionLog already is closed.
   */
  def entriesInRange(from: Long, to: Long): Vector[Array[Byte]] = if (isOpen.isOn) {
    try {
      if (from < 0) throw new IllegalArgumentException("'from' index can't be negative [" + from + "]")
      if (to < 0) throw new IllegalArgumentException("'to' index can't be negative [" + from + "]")
      if (to < from) throw new IllegalArgumentException("'to' index can't be smaller than 'from' index [" + from + "," + to + "]")
      EventHandler.debug(this, "Reading entries [%s -> %s] for log [%s]".format(from, to, logId))

      if (isAsync) {
        val future = Promise[Vector[Array[Byte]]]()
        ledger.asyncReadEntries(
          from, to,
          new AsyncCallback.ReadCallback {
            def readComplete(returnCode: Int, ledgerHandle: LedgerHandle, enumeration: Enumeration[LedgerEntry], ctx: AnyRef) {
              val future = ctx.asInstanceOf[Promise[Vector[Array[Byte]]]]
              val entries = toByteArrays(enumeration)

              if (returnCode == BKException.Code.OK) future.success(entries)
              else future.failure(BKException.create(returnCode))
            }
          },
          future)
        await(future)
      } else {
        toByteArrays(ledger.readEntries(from, to))
      }
    } catch {
      case e: Throwable ⇒ handleError(e)
    }
  } else transactionClosedError

  /**
   * Get the last entry written to this transaction log.
   *
   * Returns -1 if there has never been an entry.
   */
  def latestEntryId: Long = ledger.getLastAddConfirmed

  /**
   * Get the id for the last snapshot written to this transaction log.
   */
  def latestSnapshotId: Option[Long] = {
    try {
      val snapshotId = zkClient.readData(snapshotPath).asInstanceOf[Long]
      EventHandler.debug(this, "Retrieved latest snapshot id [%s] from transaction log [%s]".format(snapshotId, logId))
      Some(snapshotId)
    } catch {
      case e: ZkNoNodeException ⇒ None
      case e: Throwable         ⇒ handleError(e)
    }
  }

  /**
   * Delete this transaction log. So all entries but also all metadata will be removed.
   *
   * TODO: Behavior unclear what happens when already deleted (what happens to the ledger).
   * TODO: Behavior unclear what happens when already closed.
   */
  def delete() {
    if (isOpen.isOn) {
      EventHandler.debug(this, "Deleting transaction log [%s]".format(logId))
      try {
        if (isAsync) {
          bookieClient.asyncDeleteLedger(
            logId,
            new AsyncCallback.DeleteCallback {
              def deleteComplete(returnCode: Int, ctx: AnyRef) {
                (returnCode)
              }
            },
            null)
        } else {
          bookieClient.deleteLedger(logId)
        }

        //also remote everything else that belongs to this TransactionLog.
        zkClient.delete(snapshotPath)
        zkClient.delete(txLogPath)
      } catch {
        case e: Throwable ⇒ handleError(e)
      }
    }
  }

  /**
   * Close this transaction log.
   *
   * If already closed, the call is ignored.
   */
  def close() {
    isOpen switchOff {
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
          ledger.close()
        }
      } catch {
        case e: Throwable ⇒ handleError(e)
      }
    }
  }

  private def toByteArrays(enumeration: Enumeration[LedgerEntry]): Vector[Array[Byte]] = {
    var entries = Vector[Array[Byte]]()
    while (enumeration.hasMoreElements) {
      val bytes = enumeration.nextElement.getEntry
      val entry =
        if (shouldCompressData) LZF.uncompress(bytes)
        else bytes
      entries = entries :+ entry
    }
    entries
  }

  private def storeSnapshotMetaDataInZooKeeper(snapshotId: Long) {
    if (isOpen.isOn) {
      try {
        zkClient.create(snapshotPath, null, CreateMode.PERSISTENT)
      } catch {
        case e: ZkNodeExistsException ⇒ {} // do nothing
        case e: Throwable             ⇒ handleError(e)
      }

      try {
        zkClient.writeData(snapshotPath, snapshotId)
      } catch {
        case e: Throwable ⇒
          handleError(new ReplicationException(
            "Could not store transaction log snapshot meta-data in ZooKeeper for UUID [" + id + "]"))
      }
      EventHandler.debug(this, "Writing snapshot [%s] to log [%s]".format(snapshotId, logId))
    } else transactionClosedError
  }

  private def handleReturnCode(block: ⇒ Long) {
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
 * TODO: Documentation.
 */
object TransactionLog {

  val zooKeeperServers = config.getString("akka.cluster.zookeeper-server-addresses", "localhost:2181")
  val sessionTimeout = Duration(config.getInt("akka.cluster.session-timeout", 60), TIME_UNIT).toMillis.toInt
  val connectionTimeout = Duration(config.getInt("akka.cluster.connection-timeout", 60), TIME_UNIT).toMillis.toInt

  val digestType = config.getString("akka.cluster.replication.digest-type", "CRC32") match {
    case "CRC32" ⇒ BookKeeper.DigestType.CRC32
    case "MAC"   ⇒ BookKeeper.DigestType.MAC
    case unknown ⇒ throw new ConfigurationException(
      "akka.cluster.replication.digest-type is invalid [" + unknown + "], must be either 'CRC32' or 'MAC'")
  }
  val password = config.getString("akka.cluster.replication.password", "secret").getBytes("UTF-8")
  val ensembleSize = config.getInt("akka.cluster.replication.ensemble-size", 3)
  val quorumSize = config.getInt("akka.cluster.replication.quorum-size", 2)
  val snapshotFrequency = config.getInt("akka.cluster.replication.snapshot-frequency", 1000)
  val timeout = Duration(config.getInt("akka.cluster.replication.timeout", 30), TIME_UNIT).toMillis
  val shouldCompressData = config.getBool("akka.remote.use-compression", false)

  private[akka] val transactionLogNode = "/transaction-log-ids"

  private val isConnected = new Switch(false)

  @volatile
  private[akka] var bookieClient: BookKeeper = _

  @volatile
  private[akka] var zkClient: AkkaZkClient = _

  private[akka] def apply(
    ledger: LedgerHandle,
    id: String,
    isAsync: Boolean,
    replicationScheme: ReplicationScheme) =
    new TransactionLog(ledger, id, isAsync, replicationScheme)

  /**
   * Starts up the transaction log.
   */
  def start() {
    isConnected switchOn {
      bookieClient = new BookKeeper(zooKeeperServers)
      zkClient = new AkkaZkClient(zooKeeperServers, sessionTimeout, connectionTimeout)

      try {
        zkClient.create(transactionLogNode, null, CreateMode.PERSISTENT)
      } catch {
        case e: ZkNodeExistsException ⇒ {} // do nothing
        case e: Throwable             ⇒ handleError(e)
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
    }
  }

  /**
   * Shuts down the transaction log.
   */
  def shutdown() {
    isConnected switchOff {
      try {
        EventHandler.info(this, "Shutting down transaction log...")
        zkClient.close()
        bookieClient.halt()
        EventHandler.info(this, "Transaction log shut down successfully")
      } catch {
        case e: Throwable ⇒ handleError(e)
      }
    }
  }

  def transactionLogPath(id: String): String = transactionLogNode + "/" + id

  /**
   * Checks if a TransactionLog for the given id already exists.
   */
  def exists(id: String): Boolean = {
    val txLogPath = transactionLogPath(id)
    zkClient.exists(txLogPath)
  }

  /**
   * Creates a new transaction log for the 'id' specified. If a TransactionLog already exists for the id,
   * it will be overwritten.
   */
  def newLogFor(id: String, isAsync: Boolean, replicationScheme: ReplicationScheme): TransactionLog = {
    val txLogPath = transactionLogPath(id)

    val ledger = try {
      if (exists(id)) {
        //if it exists, we need to delete it first. This gives it the overwrite semantics we are looking for.
        try {
          val ledger = bookieClient.createLedger(ensembleSize, quorumSize, digestType, password)
          val txLog = TransactionLog(ledger, id, false, null)
          txLog.delete()
          txLog.close()
        } catch {
          case e: Throwable ⇒ handleError(e)
        }
      }

      val future = Promise[LedgerHandle]()
      if (isAsync) {
        bookieClient.asyncCreateLedger(
          ensembleSize, quorumSize, digestType, password,
          new AsyncCallback.CreateCallback {
            def createComplete(
              returnCode: Int,
              ledgerHandle: LedgerHandle,
              ctx: AnyRef) {
              val future = ctx.asInstanceOf[Promise[LedgerHandle]]
              if (returnCode == BKException.Code.OK) future.success(ledgerHandle)
              else future.failure(BKException.create(returnCode))
            }
          },
          future)
        await(future)
      } else {
        bookieClient.createLedger(ensembleSize, quorumSize, digestType, password)
      }
    } catch {
      case e: Throwable ⇒ handleError(e)
    }

    val logId = ledger.getId
    try {
      zkClient.create(txLogPath, null, CreateMode.PERSISTENT)
      zkClient.writeData(txLogPath, logId)
      logId //TODO: does this have any effect?
    } catch {
      case e: Throwable ⇒
        bookieClient.deleteLedger(logId) // clean up
        handleError(new ReplicationException(
          "Could not store transaction log [" + logId +
            "] meta-data in ZooKeeper for UUID [" + id + "]", e))
    }

    EventHandler.info(this, "Created new transaction log [%s] for UUID [%s]".format(logId, id))
    TransactionLog(ledger, id, isAsync, replicationScheme)
  }

  /**
   * Fetches an existing transaction log for the 'id' specified.
   *
   * @throws ReplicationException if the log with the given id doesn't exist.
   */
  def logFor(id: String, isAsync: Boolean, replicationScheme: ReplicationScheme): TransactionLog = {
    val txLogPath = transactionLogPath(id)

    val logId = try {
      val logId = zkClient.readData(txLogPath).asInstanceOf[Long]
      EventHandler.debug(this,
        "Retrieved transaction log [%s] for UUID [%s]".format(logId, id))
      logId
    } catch {
      case e: ZkNoNodeException ⇒
        handleError(new ReplicationException(
          "Transaction log for UUID [" + id + "] does not exist in ZooKeeper"))
      case e: Throwable ⇒ handleError(e)
    }

    val ledger = try {
      if (isAsync) {
        val future = Promise[LedgerHandle]()
        bookieClient.asyncOpenLedger(
          logId, digestType, password,
          new AsyncCallback.OpenCallback {
            def openComplete(returnCode: Int, ledgerHandle: LedgerHandle, ctx: AnyRef) {
              val future = ctx.asInstanceOf[Promise[LedgerHandle]]
              if (returnCode == BKException.Code.OK) future.success(ledgerHandle)
              else future.failure(BKException.create(returnCode))
            }
          },
          future)
        await(future)
      } else {
        bookieClient.openLedger(logId, digestType, password)
      }
    } catch {
      case e: Throwable ⇒ handleError(e)
    }

    TransactionLog(ledger, id, isAsync, replicationScheme)
  }

  private[akka] def await[T](future: Promise[T]): T = {
    future.await.value.get match {
      case Right(result) => result
      case Left(throwable) => handleError(throwable)
    }
  }

  private[akka] def handleError(e: Throwable): Nothing = {
    EventHandler.error(e, this, e.toString)
    throw e
  }
}

/**
 * TODO: Documentation.
 */
object LocalBookKeeperEnsemble {
  private val isRunning = new Switch(false)

  //TODO: should probably come from the config file.
  private val port = 5555

  @volatile
  private var localBookKeeper: LocalBookKeeper = _

  /**
   * Starts the LocalBookKeeperEnsemble.
   *
   * Call can safely be made when already started.
   *
   * This call will block until it is started.
   */
  def start() {
    isRunning switchOn {
      EventHandler.info(this, "Starting up LocalBookKeeperEnsemble...")
      localBookKeeper = new LocalBookKeeper(TransactionLog.ensembleSize)
      localBookKeeper.runZookeeper(port)
      localBookKeeper.initializeZookeper()
      localBookKeeper.runBookies()
      EventHandler.info(this, "LocalBookKeeperEnsemble started up successfully")
    }
  }

  /**
   * Shuts down the LocalBookKeeperEnsemble.
   *
   * Call can safely bemade when already shutdown.
   *
   * This call will block until the shutdown completes.
   */
  def shutdown() {
    isRunning switchOff {
      EventHandler.info(this, "Shutting down LocalBookKeeperEnsemble...")
      localBookKeeper.bs.foreach(_.shutdown()) // stop bookies
      localBookKeeper.zkc.close() // stop zk client
      localBookKeeper.zks.shutdown() // stop zk server
      localBookKeeper.serverFactory.shutdown() // stop zk NIOServer
      EventHandler.info(this, "LocalBookKeeperEnsemble shut down successfully")
    }
  }
}
