package akka.cluster.storage

/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
import akka.cluster.zookeeper.AkkaZkClient
import akka.AkkaException
import org.apache.zookeeper.{ KeeperException, CreateMode }
import org.apache.zookeeper.data.Stat
import java.util.concurrent.ConcurrentHashMap
import annotation.tailrec
import java.lang.{ RuntimeException, UnsupportedOperationException }

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
   * This call doesn't care about the actual version of the data.
   *
   * @param key: the key of the VersionedData to load.
   * @return the VersionedData for the given entry.
   * @throws MissingDataException if the entry with the given key doesn't exist.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def load(key: String): VersionedData

  /**
   * Loads the VersionedData for the given key and expectedVersion.
   *
   * This call can be used for optimistic locking since the version is included.
   *
   * @param key: the key of the VersionedData to load
   * @param expectedVersion the version the data to load should have.
   * @throws MissingDataException if the data with the given key doesn't exist.
   * @throws BadVersionException if the version is not the expected version.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def load(key: String, expectedVersion: Long): VersionedData

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
   * @return the version of the written data (can be used for optimistic locking).
   * @throws DataExistsException when VersionedData with the given Key already exists.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def insert(key: String, bytes: Array[Byte]): Long

  /**
   * Inserts the data if there is no data for that key, or overwrites it if it is there.
   *
   * This is the method you want to call if you just want to save something and don't
   * care about any lost update issues.
   *
   * @param key the key of the data
   * @param bytes the data to insert
   * @return the version of the written data (can be used for optimistic locking).
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def insertOrOverwrite(key: String, bytes: Array[Byte]): Long

  /**
   * Overwrites the current data for the given key. This call doesn't care about the version of the existing data.
   *
   * @param key the key of the data to overwrite
   * @param bytes the data to insert.
   * @return the version of the written data (can be used for optimistic locking).
   * @throws MissingDataException when the entry with the given key doesn't exist.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def overwrite(key: String, bytes: Array[Byte]): Long

  /**
   * Updates an existing value using an optimistic lock. So it expect the current data to have the expectedVersion
   * and only then, it will do the update.
   *
   * @param key the key of the data to update
   * @param bytes the content to write for the given key
   * @param expectedVersion the version of the content that is expected to be there.
   * @return the version of the written data (can be used for optimistic locking).
   * @throws MissingDataException if no data for the given key exists
   * @throws BadVersionException if the version if the found data doesn't match the expected version. So essentially
   * if another update was already done.
   * @throws StorageException if anything goes wrong while accessing the storage
   */
  def update(key: String, bytes: Array[Byte], expectedVersion: Long): Long
}

/**
 * The VersionedData is a container of data (some bytes) and a version (a Long).
 */
class VersionedData(val data: Array[Byte], val version: Long) {}

/**
 * An AkkaException thrown by the Storage module.
 */
class StorageException(msg: String = null, cause: java.lang.Throwable = null) extends AkkaException(msg, cause) {
  def this(msg: String) = this(msg, null);
}

/**
 * *
 * A StorageException thrown when an operation is done on a non existing node.
 */
class MissingDataException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause) {
  def this(msg: String) = this(msg, null);
}

/**
 * A StorageException thrown when an operation is done on an existing node, but no node was expected.
 */
class DataExistsException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause) {
  def this(msg: String) = this(msg, null);
}

/**
 * A StorageException thrown when an operation causes an optimistic locking failure.
 */
class BadVersionException(msg: String = null, cause: java.lang.Throwable = null) extends StorageException(msg, cause) {
  def this(msg: String) = this(msg, null);
}

/**
 * A Storage implementation based on ZooKeeper.
 *
 * The store method is atomic:
 * - so everything is written or nothing is written
 * - is isolated, so threadsafe,
 * but it will not participate in any transactions.
 *
 */
class ZooKeeperStorage(zkClient: AkkaZkClient, root: String = "/peter/storage") extends Storage {

  var path = ""

  //makes sure that the complete root exists on zookeeper.
  root.split("/").foreach(
    item ⇒ if (item.size > 0) {

      path = path + "/" + item

      if (!zkClient.exists(path)) {
        //it could be that another thread is going to create this root node as well, so ignore it when it happens.
        try {
          zkClient.create(path, "".getBytes, CreateMode.PERSISTENT)
        } catch {
          case ignore: KeeperException.NodeExistsException ⇒
        }
      }
    })

  def toZkPath(key: String): String = {
    root + "/" + key
  }

  def load(key: String) = try {
    val stat = new Stat
    val arrayOfBytes = zkClient.connection.readData(root + "/" + key, stat, false)
    new VersionedData(arrayOfBytes, stat.getVersion)
  } catch {
    case e: KeeperException.NoNodeException ⇒ throw new MissingDataException(
      String.format("Failed to load key [%s]: no data was found", key), e)
    case e: KeeperException ⇒ throw new StorageException(
      String.format("Failed to load key [%s]", key), e)
  }

  def load(key: String, expectedVersion: Long) = try {
    val stat = new Stat
    val arrayOfBytes = zkClient.connection.readData(root + "/" + key, stat, false)

    if (stat.getVersion != expectedVersion) throw new BadVersionException(
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

  def insert(key: String, bytes: Array[Byte]): Long = {
    try {
      zkClient.connection.create(root + "/" + key, bytes, CreateMode.PERSISTENT)
      //todo: how to get hold of the version.
      val version: Long = 0
      version
    } catch {
      case e: KeeperException.NodeExistsException ⇒ throw new DataExistsException(
        String.format("Failed to insert key [%s]: an entry already exists with the same key", key), e)
      case e: KeeperException ⇒ throw new StorageException(
        String.format("Failed to insert key [%s]", key), e)
    }
  }

  def exists(key: String) = try {
    zkClient.connection.exists(toZkPath(key), false)
  } catch {
    case e: KeeperException ⇒ throw new StorageException(
      String.format("Failed to check existance for key [%s]", key), e)
  }

  def update(key: String, bytes: Array[Byte], expectedVersion: Long): Long = {
    try {
      zkClient.connection.writeData(root + "/" + key, bytes, expectedVersion.asInstanceOf[Int])
      throw new RuntimeException()
    } catch {
      case e: KeeperException.BadVersionException ⇒ throw new BadVersionException(
        String.format("Failed to update key [%s]: version mismatch", key), e)
      case e: KeeperException ⇒ throw new StorageException(
        String.format("Failed to update key [%s]", key), e)
    }
  }

  def overwrite(key: String, bytes: Array[Byte]): Long = {
    try {
      zkClient.connection.writeData(root + "/" + key, bytes)
      -1L
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

    if (result.version != expectedVersion) throw new BadVersionException(
      "Failed to load key [" + key + "]: version mismatch, expected [" + result.version + "] " +
        "but found [" + expectedVersion + "]")

    result
  }

  def exists(key: String) = map.containsKey(key)

  def insert(key: String, bytes: Array[Byte]): Long = {
    val version: Long = InMemoryStorage.InitialVersion
    val result = new VersionedData(bytes, version)

    val previous = map.putIfAbsent(key, result)
    if (previous != null) throw new DataExistsException(
      String.format("Failed to insert key [%s]: the key already has been inserted previously", key))

    version
  }

  @tailrec
  def update(key: String, bytes: Array[Byte], expectedVersion: Long): Long = {
    val found = map.get(key)

    if (found == null) throw new MissingDataException(
      String.format("Failed to update key [%s], no previous entry exist", key))

    if (expectedVersion != found.version) throw new BadVersionException(
      "Failed to update key [" + key + "]: version mismatch, expected [" + expectedVersion + "]" +
        " but found [" + found.version + "]")

    val newVersion: Long = expectedVersion + 1

    if (map.replace(key, found, new VersionedData(bytes, newVersion))) newVersion
    else update(key, bytes, expectedVersion)
  }

  @tailrec
  def overwrite(key: String, bytes: Array[Byte]): Long = {
    val current = map.get(key)

    if (current == null) throw new MissingDataException(
      String.format("Failed to overwrite key [%s], no previous entry exist", key))

    val update = new VersionedData(bytes, current.version + 1)

    if (map.replace(key, current, update)) update.version
    else overwrite(key, bytes)
  }

  def insertOrOverwrite(key: String, bytes: Array[Byte]): Long = {
    val version = InMemoryStorage.InitialVersion
    val result = new VersionedData(bytes, version)

    val previous = map.putIfAbsent(key, result)

    if (previous == null) result.version
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
