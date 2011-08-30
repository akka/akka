package akka.cluster.storage

import org.scalatest.matchers.MustMatchers
import akka.actor.Actor
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll, WordSpec }
import org.I0Itec.zkclient.ZkServer
//import zookeeper.AkkaZkClient
import akka.cluster.storage.StorageTestUtils._
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class ZooKeeperStorageSpec extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  val dataPath = "_akka_cluster/data"
  val logPath = "_akka_cluster/log"
  var zkServer: ZkServer = _
  //var zkClient: AkkaZkClient = _
  val idGenerator = new AtomicLong

  def generateKey: String = {
    "foo" + idGenerator.incrementAndGet()
  }

  override def beforeAll() {
    /*new File(dataPath).delete()
    new File(logPath).delete()

    try {
      zkServer = Cluster.startLocalCluster(dataPath, logPath)
      Thread.sleep(5000)
      Actor.cluster.start()
      zkClient = Cluster.newZkClient()
    } catch {
      case e ⇒ e.printStackTrace()
    }*/
  }

  override def afterAll() {
    /*zkClient.close()
    Actor.cluster.shutdown()
    ClusterDeployer.shutdown()
    Cluster.shutdownLocalCluster()
    Actor.registry.local.shutdownAll() */
  }

  /*
  "unversioned load" must {
    "throw MissingDataException if non existing key" in {
      val storage = new ZooKeeperStorage(zkClient)

      try {
        storage.load(generateKey)
        fail()
      } catch {
        case e: MissingDataException ⇒
      }
    }

    "return VersionedData if key existing" in {
      val storage = new ZooKeeperStorage(zkClient)
      val key = generateKey
      val value = "somevalue".getBytes
      storage.insert(key, value)

      val result = storage.load(key)
      //todo: strange that the implicit store is not found
      assertContent(key, value, result.version)(storage)
    }
  } */

  /*"overwrite" must {

    "throw MissingDataException when there doesn't exist an entry to overwrite" in {
      val storage = new ZooKeeperStorage(zkClient)
      val key = generateKey
      val value = "value".getBytes

      try {
        storage.overwrite(key, value)
        fail()
      } catch {
        case e: MissingDataException ⇒
      }

      assert(!storage.exists(key))
    }

    "overwrite if there is an existing value" in {
      val storage = new ZooKeeperStorage(zkClient)
      val key = generateKey
      val oldValue = "oldvalue".getBytes

      storage.insert(key, oldValue)
      val newValue = "newValue".getBytes

      val result = storage.overwrite(key, newValue)
      //assertContent(key, newValue, result.version)(storage)
    }
  }

  "insert" must {

    "place a new value when non previously existed" in {
      val storage = new ZooKeeperStorage(zkClient)
      val key = generateKey
      val oldValue = "oldvalue".getBytes
      storage.insert(key, oldValue)

      val result = storage.load(key)
      assertContent(key, oldValue)(storage)
      assert(InMemoryStorage.InitialVersion == result.version)
    }

    "throw DataExistsException when there already exists an entry with the same key" in {
      val storage = new ZooKeeperStorage(zkClient)
      val key = generateKey
      val oldValue = "oldvalue".getBytes

      val initialVersion = storage.insert(key, oldValue)
      val newValue = "newValue".getBytes

      try {
        storage.insert(key, newValue)
        fail()
      } catch {
        case e: DataExistsException ⇒
      }

      assertContent(key, oldValue, initialVersion)(storage)
    }
  }      */

}
