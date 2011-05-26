package akka.cluster

import zookeeper.AkkaZkClient
import akka.AkkaException
import org.apache.zookeeper.{ KeeperException, CreateMode }
import org.apache.zookeeper.data.Stat
import scala.Some
import java.util.concurrent.ConcurrentHashMap
import org.apache.zookeeper.KeeperException.NoNodeException

/**
 * Simple abstraction to store an Array of bytes based on some String key.
 *
 * Nothing is being said about ACID, transactions etc. It depends on the implementation
 * of this Storage interface of what is and isn't done on the lowest level.
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
   * @throws NodeExistsException when a Node with the given Key already exists.
   */
  def insert(key: String, bytes: Array[Byte]): Unit

  /**
   * Stores a array of bytes based on some key.
   *
   * @throws MissingNodeException when the Node with the given key doesn't exist.
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
class RawStorageException(msg: String = null, cause: java.lang.Throwable = null) extends AkkaException(msg, cause)

/**
 * *
 * A RawStorageException thrown when an operation is done on a non existing node.
 */
class MissingNodeException(msg: String = null, cause: java.lang.Throwable = null) extends RawStorageException(msg, cause)

/**
 * A RawStorageException thrown when an operation is done on an existing node, but no node was expected.
 */
class NodeExistsException(msg: String = null, cause: java.lang.Throwable = null) extends RawStorageException(msg, cause)

/**
 * A RawStorage implementation based on ZooKeeper.
 *
 * The store method is atomic:
 * - so everything is written or nothing is written
 * - is isolated, so threadsafe,
 * but it will not participate in any transactions.
 * //todo: unclear, is only a single connection used in the JVM??
 *
 */
class ZooKeeperRawStorage(zkClient: AkkaZkClient) extends RawStorage {

  override def load(key: String) = try {
    Some(zkClient.connection.readData(key, new Stat, false))
  } catch {
    case e: KeeperException.NoNodeException ⇒ None
    case e: KeeperException                 ⇒ throw new RawStorageException("failed to load key" + key, e)
  }

  override def insert(key: String, bytes: Array[Byte]) {
    try {
      zkClient.connection.create(key, bytes, CreateMode.PERSISTENT);
    } catch {
      case e: KeeperException.NodeExistsException ⇒ throw new NodeExistsException("failed to insert key" + key, e)
      case e: KeeperException                     ⇒ throw new RawStorageException("failed to insert key" + key, e)
    }
  }

  override def update(key: String, bytes: Array[Byte]) {
    try {
      zkClient.connection.writeData(key, bytes)
    } catch {
      case e: KeeperException.NoNodeException ⇒ throw new MissingNodeException("failed to update key", e)
      case e: KeeperException                 ⇒ throw new RawStorageException("failed to update key", e)
    }
  }
}

/**
 * An in memory {@link RawStore} implementation. Useful for testing purposes.
 */
class InMemoryRawStorage extends RawStorage {

  private val map = new ConcurrentHashMap[String, Array[Byte]]()

  def load(key: String) = Option(map.get(key))

  def insert(key: String, bytes: Array[Byte]) {
    val previous = map.putIfAbsent(key, bytes)
    if (previous != null) throw new NodeExistsException("failed to insert key " + key)
  }

  def update(key: String, bytes: Array[Byte]) {
    val previous = map.put(key, bytes)
    if (previous == null) throw new NoNodeException("failed to update key " + key)
  }
}

//TODO: To minimize the number of dependencies, should the RawStorage not be placed in a seperate module?
//class VoldemortRawStorage(storeClient: StoreClient) extends RawStorage {
//
//  def load(Key: String) = {
//    try {
//
//    } catch {
//      case
//    }
//  }
//
//  override def insert(key: String, bytes: Array[Byte]) {
//    throw new UnsupportedOperationException()
//  }
//
//  def update(key: String, bytes: Array[Byte]) {
//    throw new UnsupportedOperationException()
//  }
//}