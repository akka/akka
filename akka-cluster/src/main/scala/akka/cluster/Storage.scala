package akka.cluster

import zookeeper.AkkaZkClient
import akka.AkkaException
import org.apache.zookeeper.{ KeeperException, CreateMode }
import org.apache.zookeeper.data.Stat
import java.util.concurrent.ConcurrentHashMap
import org.apache.zookeeper.KeeperException.NoNodeException
import java.lang.UnsupportedOperationException
import annotation.tailrec

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
trait Storage {

  /**
   * Loads the given entry.
   *
   * @param key: the key of the data to load.
   * @return the VersionedData for the given key.
   * @throws NoNodeExistsException if the data with the given key doesn't exist.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def load(key: String): VersionedData

  /**
   * Loads the data for the given key and version.
   *
   * @param key: the key of the data to load
   * @param version the version of the data to load
   * @throws NoNodeExistsException if the data with the given key doesn't exist.
   * @throws VersioningMismatchStorageException if the version of the data is not the same as the given data.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def load(key: String, version: Long): VersionedData

  /**
   * Checks if a value with the given key exists.
   *
   * @param key the key to check the existence for.
   * @return true if exists, false if not.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def exists(key: String): Boolean

  /**
   * Inserts a byte-array based on some key.
   *
   * @param key the key of the Data to insert.
   * @param bytes the data to insert.
   * @return the version of the inserted data
   * @throws NodeExistsException when a Node with the given Key already exists.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def insert(key: String, bytes: Array[Byte]): VersionedData

  /**
   * Stores a array of bytes based on some key.
   *
   * @throws MissingNodeException when the Node with the given key doesn't exist.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def update(key: String, bytes: Array[Byte]): VersionedData

  /**
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def update(key: String, versionedData: VersionedData): Unit
}

/**
 * The VersionedData is a container of data (some bytes) and a version (a Long).
 */
class VersionedData(val data: Array[Byte], val version: Long) {

  /**
   * Creates an updated VersionedData. What happens is that a new VersionedData object is created with the newData
   * and a version that is one higher than the current version.
   */
  def createUpdate(newData: Array[Byte]): VersionedData = new VersionedData(newData, version + 1)
}

/**
 * An AkkaException thrown by the Storage module.
 */
class StorageException(msg: String = null, cause: java.lang.Throwable = null) extends AkkaException(msg, cause)

/**
 * *
 * A StorageException thrown when an operation is done on a non existing node.
 */
class MissingNodeException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause)

/**
 * A StorageException thrown when an operation is done on an existing node, but no node was expected.
 */
class NodeExistsException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause)

/**
 * A StorageException thrown when an operation causes an optimistic locking failure.
 */
class VersioningMismatchStorageException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause)

/**
 * A Storage implementation based on ZooKeeper.
 *
 * The store method is atomic:
 * - so everything is written or nothing is written
 * - is isolated, so threadsafe,
 * but it will not participate in any transactions.
 * //todo: unclear, is only a single connection used in the JVM??
 *
 */
class ZooKeeperStorage(zkClient: AkkaZkClient) extends Storage {

  def load(key: String) = try {
    val arrayOfBytes: Array[Byte] = zkClient.connection.readData(key, new Stat, false)
    //Some(arrayOfBytes)
    throw new UnsupportedOperationException()
  } catch {
    //todo: improved error messaged
    case e: KeeperException.NoNodeException ⇒ throw new MissingNodeException("Failed to load key", e)
    case e: KeeperException                 ⇒ throw new StorageException("failed to load key " + key, e)
  }

  def load(key: String, version: Long) = {
    throw new UnsupportedOperationException()
  }

  def insert(key: String, bytes: Array[Byte]): VersionedData = {
    try {
      zkClient.connection.create(key, bytes, CreateMode.PERSISTENT);
      throw new UnsupportedOperationException()
    } catch {
      //todo: improved error messaged
      case e: KeeperException.NodeExistsException ⇒ throw new NodeExistsException("failed to insert key " + key, e)
      case e: KeeperException                     ⇒ throw new StorageException("failed to insert key " + key, e)
    }
  }

  def exists(key: String) = try {
    zkClient.connection.exists(key, false)
  } catch {
    //todo: improved error messaged
    case e: KeeperException ⇒ throw new StorageException("failed to check for existance on key " + key, e)
  }

  def update(key: String, versionedData: VersionedData) = try {
    zkClient.connection.writeData(key, versionedData.data, versionedData.version.asInstanceOf[Int])
  } catch {
    //todo: improved error messaged
    case e: KeeperException.BadVersionException ⇒ throw new VersioningMismatchStorageException()
    case e: KeeperException                     ⇒ throw new StorageException("failed to check for existance on key " + key, e)
  }

  def update(key: String, bytes: Array[Byte]): VersionedData = {
    try {
      zkClient.connection.writeData(key, bytes)
      throw new RuntimeException()
    } catch {
      //todo: improved error messaged
      case e: KeeperException.NoNodeException ⇒ throw new MissingNodeException("failed to update key ", e)
      case e: KeeperException                 ⇒ throw new StorageException("failed to update key ", e)
    }
  }
}

object InMemoryStorage {
  val InitialVersion = 0;
}

/**
 * An in memory {@link RawStore} implementation. Useful for testing purposes.
 */
final class InMemoryStorage extends Storage {

  private val map = new ConcurrentHashMap[String, VersionedData]()

  def load(key: String) = {
    val result = map.get(key)

    if (result == null) throw new MissingNodeException(
      String.format("Failed to load data for key [%s]: no data was found", key))

    result
  }

  def load(key: String, expectedVersion: Long) = {
    val result = load(key)

    if (result.version != expectedVersion) throw new VersioningMismatchStorageException(
      "Failed to load data for key [" + key + "]: version mismatch, expected [" + result.version + "] " +
        "but found [" + expectedVersion + "]")

    result
  }

  def exists(key: String) = map.containsKey(key)

  def insert(key: String, bytes: Array[Byte]): VersionedData = {
    val version: Long = InMemoryStorage.InitialVersion
    val result = new VersionedData(bytes, version)

    val previous = map.putIfAbsent(key, result)
    if (previous != null) throw new NodeExistsException(
      String.format("Failed to insert key [%s]: the key already has been inserted previously", key))

    result
  }

  @tailrec
  def update(key: String, updatedData: VersionedData) {
    val currentData = map.get(key)

    if (currentData == null) throw new MissingNodeException(
      String.format("Failed to update data for key [%s], no previous entry exist", key))

    val expectedVersion = currentData.version + 1
    if (expectedVersion != updatedData.version) throw new VersioningMismatchStorageException(
      "Failed to update data for key [" + key + "]: version mismatch, expected [" + expectedVersion + "]" +
        " but found [" + updatedData.version + "]")

    if (!map.replace(key, currentData, updatedData)) update(key, updatedData)
  }

  def update(key: String, bytes: Array[Byte]): VersionedData = {
    if (map.get(key) == null) throw new NoNodeException(
      String.format("Failed to update key [%s]: no previous insert of this key exists", key))

    //smap.put(key, bytes)
    throw new UnsupportedOperationException()
  }
}

//TODO: To minimize the number of dependencies, should the Storage not be placed in a seperate module?
//class VoldemortRawStorage(storeClient: StoreClient) extends Storage {
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