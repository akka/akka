package akka.cluster

import zookeeper.AkkaZkClient
import java.lang.UnsupportedOperationException
import akka.AkkaException
import org.apache.zookeeper.{KeeperException, CreateMode}
import org.apache.zookeeper.data.Stat
import scala.Some

/**
 * Simple abstraction to store an Array of bytes based on some String key.
 *
 * Nothing is being said about ACID, transactions etc.
 *
 * TODO: Perhaps add a version to the store to prevent lost updates using optimistic locking.
 * (This is supported by ZooKeeper).
 * TODO: Class is up for better names.
 * TODO: Instead of a String as key, perhaps also a byte-array.
 */
trait RawStorage {

  /**
   * Inserts a byte-array based on some key.
   *
   * TODO: What happens when given key already exists
   */
  def insert(key: String, bytes: Array[Byte]): Unit

  /**
   * Stores a array of bytes based on some key.
   *
   * TODO: What happens when the given key doesn't exist yet
   */
  def update(key: String, bytes: Array[Byte]): Unit

  /**
   * Loads the given entry. If it exists, a 'Some[Array[Byte]]' will be returned, else a None.
   */
  def load(key: String): Option[Array[Byte]]
}

/**
 * An AkkaException thrown by the RawStorage module.
 */
class RawStorageException(msg: String = null, cause: Throwable = null) extends AkkaException(msg, cause)

/***
 * A RawStorageException thrown when an operation is done on a non existing node.
 */
class MissingNodeException(msg: String = null, cause: Throwable = null) extends RawStorageException(msg, cause)

/**
 * A RawStorageException thrown when an operation is done on an existing node, but no node was expected.
 */
class NodeExistsException(msg: String = null, cause: Throwable = null) extends RawStorageException(msg, cause)


/**
 * A RawStorage implementation based on ZooKeeper.
 *
 * The store method is atomic:
 * - so everything is written or nothing is written
 * - is isolated, so threadsafe,
 * but it will not participate in any transactions.
 * //todo: unclear, is only a single connection used in the JVM??

 */
class ZooKeeperRawStorage(zkClient: AkkaZkClient) extends RawStorage {

  override def insert(key: String, bytes: Array[Byte]) {
    try {
      zkClient.connection.create(key, bytes, CreateMode.PERSISTENT);
    } catch {
      case e: KeeperException.NodeExistsException => throw new NodeExistsException(e)
      case e: KeeperException => throw new RawStorageException(e)
    }
  }

  override def load(key: String) = try {
    Some(zkClient.connection.readData(key, new Stat, false))
  } catch {
    case e: KeeperException.NoNodeException => None
    case e: KeeperException => throw new RawStorageException(e)
  }

  override def update(key: String, bytes: Array[Byte]) {
    try {
      zkClient.connection.writeData(key, bytes)
    } catch {
      case e: KeeperException.NoNodeException => throw new MissingNodeException(e)
      case e: KeeperException => throw new RawStorageException(e)
    }
  }
}


class VoldemortRawStorage extends RawStorage {

  def load(Key: String) = {
    throw new UnsupportedOperationException()
  }

  override def insert(key: String, bytes: Array[Byte]) {
    throw new UnsupportedOperationException()
  }

  def update(key: String, bytes: Array[Byte]) {
    throw new UnsupportedOperationException()
  }
}