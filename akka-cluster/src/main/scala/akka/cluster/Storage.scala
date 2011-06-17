/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import zookeeper.AkkaZkClient
import akka.AkkaException
import org.apache.zookeeper.{ KeeperException, CreateMode }
import org.apache.zookeeper.data.Stat
import java.util.concurrent.ConcurrentHashMap
import annotation.tailrec
import java.lang.{ UnsupportedOperationException, RuntimeException }

/**
 * Simple abstraction to store an Array of bytes based on some String key.
 *
 * Nothing is being said about ACID, transactions etc. It depends on the implementation
 * of this Storage interface of what is and isn't done on the lowest level.
 *
 * The amount of data that is allowed to be insert/updated is implementation specific. The InMemoryStorage
 * has no limits, but the ZooKeeperStorage has a maximum size of 1 mb.
 *
 * TODO: Class is up for better names.
 * TODO: Instead of a String as key, perhaps also a byte-array.
 */
trait Storage {

  /**
   * Loads the VersionedData for the given key.
   *
   * @param key: the key of the VersionedData to load.
   * @return the VersionedData for the given entry.
   * @throws MissingDataException if the entry with the given key doesn't exist.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def load(key: String): VersionedData

  /**
   * Loads the VersionedData for the given key and version.
   *
   * @param key: the key of the VersionedData to load
   * @param version the version of the VersionedData to load
   * @throws MissingDataException if the data with the given key doesn't exist.
   * @throws VersioningException if the version of the data is not the same as the given data.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def load(key: String, version: Long): VersionedData

  /**
   * Checks if a VersionedData with the given key exists.
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
   * @return the VersionedData
   * @throws DataExistsException when VersionedData with the given Key already exists.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def insert(key: String, bytes: Array[Byte]): VersionedData

  /**
   * Inserts the data if there is no data for that key, or overwrites it if it is there.
   *
   * This is the method you want to call if you just want to save something and don't
   * care about any lost update issues.
   *
   * @param key the key of the data
   * @param bytes the data to insert
   * @return the VersionedData that was stored.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def insertOrOverwrite(key: String, bytes: Array[Byte]): VersionedData

  /**
   * Overwrites the current data for the given key.
   *
   * @param key the key of the data to overwrite
   * @param bytes the data to insert.
   * @throws ` when the entry with the given key doesn't exist.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def overwrite(key: String, bytes: Array[Byte]): VersionedData

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
class MissingDataException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause)

/**
 * A StorageException thrown when an operation is done on an existing node, but no node was expected.
 */
class DataExistsException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause)

/**
 * A StorageException thrown when an operation causes an optimistic locking failure.
 */
class VersioningException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause)

/**
 * A Storage implementation based on ZooKeeper.
 *
 * The store method is atomic:
 * - so everything is written or nothing is written
 * - is isolated, so threadsafe,
 * but it will not participate in any transactions.
 *
 */
class ZooKeeperStorage(zkClient: AkkaZkClient) extends Storage {

  def load(key: String) = try {
    val stat = new Stat
    val arrayOfBytes = zkClient.connection.readData(key, stat, false)
    new VersionedData(arrayOfBytes, stat.getVersion)
  } catch {
    case e: KeeperException.NoNodeException ⇒ throw new MissingDataException(
      String.format("Failed to load key [%s]: no data was found", key), e)
    case e: KeeperException ⇒ throw new StorageException(
      String.format("Failed to load key [%s]", key), e)
  }

  def load(key: String, expectedVersion: Long) = try {
    val stat = new Stat
    val arrayOfBytes = zkClient.connection.readData(key, stat, false)

    if (stat.getVersion != expectedVersion) throw new VersioningException(
      "Failed to update key [" + key + "]: version mismatch, expected [" + expectedVersion + "]" +
        " but found [" + stat.getVersion + "]")

    new VersionedData(arrayOfBytes, stat.getVersion)
  } catch {
    case e: KeeperException.NoNodeException ⇒ throw new MissingDataException(
      String.format("Failed to load key [%s]: no data was found", key), e)
    case e: KeeperException ⇒ throw new StorageException(
      String.format("Failed to load key [%s]", key), e)
  }

  def insertOrOverwrite(key: String, bytes: Array[Byte]) = {
    try {
      throw new UnsupportedOperationException()
    } catch {
      case e: KeeperException.NodeExistsException ⇒ throw new DataExistsException(
        String.format("Failed to insert key [%s]: an entry already exists with the same key", key), e)
      case e: KeeperException ⇒ throw new StorageException(
        String.format("Failed to insert key [%s]", key), e)
    }
  }

  def insert(key: String, bytes: Array[Byte]): VersionedData = {
    try {
      zkClient.connection.create(key, bytes, CreateMode.PERSISTENT)
      //todo: how to get hold of the reference.
      val version: Long = 0
      new VersionedData(bytes, version)
    } catch {
      case e: KeeperException.NodeExistsException ⇒ throw new DataExistsException(
        String.format("Failed to insert key [%s]: an entry already exists with the same key", key), e)
      case e: KeeperException ⇒ throw new StorageException(
        String.format("Failed to insert key [%s]", key), e)
    }
  }

  def exists(key: String) = try {
    zkClient.connection.exists(key, false)
  } catch {
    case e: KeeperException ⇒ throw new StorageException(
      String.format("Failed to check existance for key [%s]", key), e)
  }

  def update(key: String, versionedData: VersionedData) {
    try {
      zkClient.connection.writeData(key, versionedData.data, versionedData.version.asInstanceOf[Int])
    } catch {
      case e: KeeperException.BadVersionException ⇒ throw new VersioningException(
        String.format("Failed to update key [%s]: version mismatch", key), e)
      case e: KeeperException ⇒ throw new StorageException(
        String.format("Failed to update key [%s]", key), e)
    }
  }

  def overwrite(key: String, bytes: Array[Byte]): VersionedData = {
    try {
      zkClient.connection.writeData(key, bytes)
      throw new RuntimeException()
    } catch {
      case e: KeeperException.NoNodeException ⇒ throw new MissingDataException(
        String.format("Failed to overwrite key [%s]: a previous entry already exists", key), e)
      case e: KeeperException ⇒ throw new StorageException(
        String.format("Failed to overwrite key [%s]", key), e)
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

    if (result == null) throw new MissingDataException(
      String.format("Failed to load key [%s]: no data was found", key))

    result
  }

  def load(key: String, expectedVersion: Long) = {
    val result = load(key)

    if (result.version != expectedVersion) throw new VersioningException(
      "Failed to load key [" + key + "]: version mismatch, expected [" + result.version + "] " +
        "but found [" + expectedVersion + "]")

    result
  }

  def exists(key: String) = map.containsKey(key)

  def insert(key: String, bytes: Array[Byte]): VersionedData = {
    val version: Long = InMemoryStorage.InitialVersion
    val result = new VersionedData(bytes, version)

    val previous = map.putIfAbsent(key, result)
    if (previous != null) throw new DataExistsException(
      String.format("Failed to insert key [%s]: the key already has been inserted previously", key))

    result
  }

  @tailrec
  def update(key: String, updatedData: VersionedData) {
    val currentData = map.get(key)

    if (currentData == null) throw new MissingDataException(
      String.format("Failed to update key [%s], no previous entry exist", key))

    val expectedVersion = currentData.version + 1
    if (expectedVersion != updatedData.version) throw new VersioningException(
      "Failed to update key [" + key + "]: version mismatch, expected [" + expectedVersion + "]" +
        " but found [" + updatedData.version + "]")

    if (!map.replace(key, currentData, updatedData)) update(key, updatedData)
  }

  @tailrec
  def overwrite(key: String, bytes: Array[Byte]): VersionedData = {
    val currentData = map.get(key)

    if (currentData == null) throw new MissingDataException(
      String.format("Failed to overwrite key [%s], no previous entry exist", key))

    val newData = currentData.createUpdate(bytes)
    if (map.replace(key, currentData, newData)) newData else overwrite(key, bytes)
  }

  def insertOrOverwrite(key: String, bytes: Array[Byte]): VersionedData = {
    val version = InMemoryStorage.InitialVersion
    val result = new VersionedData(bytes, version)

    val previous = map.putIfAbsent(key, result)

    if (previous == null) result
    else overwrite(key, bytes)
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