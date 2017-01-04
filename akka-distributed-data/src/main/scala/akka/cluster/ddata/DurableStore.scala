/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.DeadLetterSuppression
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.Replicator.ReplicatorMessage
import akka.io.DirectByteBufferPool
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import akka.util.ByteString
import akka.util.OptionVal
import com.typesafe.config.Config
import org.lmdbjava.DbiFlags
import org.lmdbjava.Env
import org.lmdbjava.EnvFlags
import org.lmdbjava.Txn

/**
 * An actor implementing the durable store for the Distributed Data `Replicator`
 * has to implement the protocol with the messages defined here.
 *
 * At startup the `Replicator` creates the durable store actor and sends the
 * `Load` message to it. It must then reply with 0 or more `LoadData` messages
 * followed by one `LoadAllCompleted` message to the `sender` (the `Replicator`).
 *
 * If the `LoadAll` fails it can throw `LoadFailed` and the `Replicator` supervisor
 * will stop itself and the durable store.
 *
 * When the `Replicator` needs to store a value it sends a `Store` message
 * to the durable store actor, which must then reply with the `successMsg` or
 * `failureMsg` to the `replyTo`.
 */
object DurableStore {

  /**
   * Request to store an entry. It optionally contains a `StoreReply`, which
   * should be used to signal success or failure of the operation to the contained
   * `replyTo` actor.
   */
  final case class Store(key: String, data: ReplicatedData, reply: Option[StoreReply])
  final case class StoreReply(successMsg: Any, failureMsg: Any, replyTo: ActorRef)

  /**
   * Request to load all entries.
   *
   * It must reply with 0 or more `LoadData` messages
   * followed by one `LoadAllCompleted` message to the `sender` (the `Replicator`).
   *
   * If the `LoadAll` fails it can throw `LoadFailed` and the `Replicator` supervisor
   * will stop itself and the durable store.
   */
  case object LoadAll
  final case class LoadData(data: Map[String, ReplicatedData])
  case object LoadAllCompleted
  class LoadFailed(message: String, cause: Throwable) extends RuntimeException(message) {
    def this(message: String) = this(message, null)
  }

  /**
   * Wrapper class for serialization of a data value.
   * The `ReplicatorMessageSerializer` will serialize/deserialize
   * the wrapped `ReplicatedData` including its serializerId and
   * manifest.
   */
  final class DurableDataEnvelope(val data: ReplicatedData) extends ReplicatorMessage {
    override def toString(): String = s"DurableDataEnvelope($data)"
    override def hashCode(): Int = data.hashCode
    override def equals(o: Any): Boolean = o match {
      case other: DurableDataEnvelope ⇒ data == other.data
      case _                          ⇒ false
    }
  }
}

object LmdbDurableStore {
  def props(config: Config): Props =
    Props(new LmdbDurableStore(config))

  private case object WriteBehind extends DeadLetterSuppression
}

final class LmdbDurableStore(config: Config) extends Actor with ActorLogging {
  import DurableStore._
  import LmdbDurableStore.WriteBehind

  val serialization = SerializationExtension(context.system)
  val serializer = serialization.serializerFor(classOf[DurableDataEnvelope]).asInstanceOf[SerializerWithStringManifest]
  val manifest = serializer.manifest(new DurableDataEnvelope(Replicator.Internal.DeletedData))

  val writeBehindInterval = config.getString("lmdb.write-behind-interval").toLowerCase match {
    case "off" ⇒ Duration.Zero
    case _     ⇒ config.getDuration("lmdb.write-behind-interval", MILLISECONDS).millis
  }

  val env: Env[ByteBuffer] = {
    val mapSize = config.getBytes("lmdb.map-size")
    val dir = config.getString("lmdb.dir") match {
      case path if path.endsWith("ddata") ⇒
        new File(s"$path-${context.system.name}-${self.path.parent.name}-${Cluster(context.system).selfAddress.port.get}")
      case path ⇒
        new File(path)
    }
    dir.mkdirs()
    Env.create()
      .setMapSize(mapSize)
      .setMaxDbs(1)
      .open(dir, EnvFlags.MDB_NOLOCK)
  }

  val db = env.openDbi("ddata", DbiFlags.MDB_CREATE)

  val keyBuffer = ByteBuffer.allocateDirect(env.getMaxKeySize)
  var valueBuffer = ByteBuffer.allocateDirect(100 * 1024) // will grow when needed

  def ensureValueBufferSize(size: Int): Unit = {
    if (valueBuffer.remaining < size) {
      DirectByteBufferPool.tryCleanDirectByteBuffer(valueBuffer)
      valueBuffer = ByteBuffer.allocateDirect(size * 2)
    }
  }

  // pending write behind
  val pending = new java.util.HashMap[String, ReplicatedData]

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    // Load is only done on first start, not on restart
    context.become(active)
  }

  override def postStop(): Unit = {
    super.postStop()
    writeBehind()
    Try(db.close())
    Try(env.close())
    DirectByteBufferPool.tryCleanDirectByteBuffer(keyBuffer)
    DirectByteBufferPool.tryCleanDirectByteBuffer(valueBuffer)
  }

  def receive = init

  def init: Receive = {
    case LoadAll ⇒
      val t0 = System.nanoTime()
      val tx = env.txnRead()
      try {
        val iter = db.iterate(tx)
        try {
          var n = 0
          val loadData = LoadData(iter.asScala.map { entry ⇒
            n += 1
            val keyArray = Array.ofDim[Byte](entry.key.remaining)
            entry.key.get(keyArray)
            val key = new String(keyArray, ByteString.UTF_8)
            val valArray = Array.ofDim[Byte](entry.`val`.remaining)
            entry.`val`.get(valArray)
            val envelope = serializer.fromBinary(valArray, manifest).asInstanceOf[DurableDataEnvelope]
            key → envelope.data
          }.toMap)
          if (loadData.data.nonEmpty)
            sender() ! loadData
          sender() ! LoadAllCompleted
          if (log.isDebugEnabled)
            log.debug("load all of [{}] entries took [{} ms]", n,
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime - t0))
          context.become(active)
        } finally {
          Try(iter.close())
        }
      } catch {
        case NonFatal(e) ⇒
          throw new LoadFailed("failed to load durable distributed-data", e)
      } finally {
        Try(tx.close())
      }
  }

  def active: Receive = {
    case Store(key, data, reply) ⇒
      try {
        if (writeBehindInterval.length == 0) {
          dbPut(OptionVal.None, key, data)
        } else {
          if (pending.isEmpty)
            context.system.scheduler.scheduleOnce(writeBehindInterval, self, WriteBehind)(context.system.dispatcher)
          pending.put(key, data)
        }
        reply match {
          case Some(StoreReply(successMsg, _, replyTo)) ⇒
            replyTo ! successMsg
          case None ⇒
        }
      } catch {
        case NonFatal(e) ⇒
          log.error(e, "failed to store [{}]", key)
          reply match {
            case Some(StoreReply(_, failureMsg, replyTo)) ⇒
              replyTo ! failureMsg
            case None ⇒
          }
      }

    case WriteBehind ⇒
      writeBehind()
  }

  def dbPut(tx: OptionVal[Txn[ByteBuffer]], key: String, data: ReplicatedData): Unit = {
    try {
      keyBuffer.put(key.getBytes(ByteString.UTF_8)).flip()
      val value = serializer.toBinary(new DurableDataEnvelope(data))
      ensureValueBufferSize(value.length)
      valueBuffer.put(value).flip()
      tx match {
        case OptionVal.None    ⇒ db.put(keyBuffer, valueBuffer)
        case OptionVal.Some(t) ⇒ db.put(t, keyBuffer, valueBuffer)
      }
    } finally {
      keyBuffer.clear()
      valueBuffer.clear()
    }
  }

  def writeBehind(): Unit = {
    if (!pending.isEmpty()) {
      val t0 = System.nanoTime()
      val tx = env.txnWrite()
      try {
        val iter = pending.entrySet.iterator
        while (iter.hasNext) {
          val entry = iter.next()
          dbPut(OptionVal.Some(tx), entry.getKey, entry.getValue)
        }
        tx.commit()
        if (log.isDebugEnabled)
          log.debug("store and commit of [{}] entries took [{} ms]", pending.size,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime - t0))
      } catch {
        case NonFatal(e) ⇒
          import scala.collection.JavaConverters._
          log.error(e, "failed to store [{}]", pending.keySet.asScala.mkString(","))
          tx.abort()
      } finally {
        pending.clear()
      }
    }
  }

}

