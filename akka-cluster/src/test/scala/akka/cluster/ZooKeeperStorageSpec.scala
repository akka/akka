package akka.cluster

import org.scalatest.matchers.MustMatchers
import akka.actor.Actor
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll, WordSpec }
import org.I0Itec.zkclient.ZkServer
import zookeeper.AkkaZkClient

class ZooKeeperStorageSpec extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  val dataPath = "_akka_cluster/data"
  val logPath = "_akka_cluster/log"
  var zkServer: ZkServer = _
  var zkClient: AkkaZkClient = _
  /*
  override def beforeAll() {
    try {
      zkServer = Cluster.startLocalCluster(dataPath, logPath)
      Thread.sleep(5000)
      Actor.cluster.start()
      zkClient = Cluster.newZkClient()
    } catch {
      case e ⇒ e.printStackTrace()
    }
  }

  override def afterAll() {
    zkClient.close()
    Actor.cluster.shutdown()
    ClusterDeployer.shutdown()
    Cluster.shutdownLocalCluster()
    Actor.registry.local.shutdownAll()
  }
*/
  "unversioned load" must {
    "throw MissingDataException if non existing key" in {
      //      val store = new ZooKeeperStorage(zkClient)

      //try {
      //  store.load("foo")
      //  fail()
      //} catch {
      //  case e: MissingDataException ⇒
      //}
    }

    /*
    "return VersionedData if key existing" in {
      val storage = new InMemoryStorage()
      val key = "somekey"
      val value = "somevalue".getBytes
      storage.insert(key, value)

      val result = storage.load(key)
      //todo: strange that the implicit store is not found
      assertContent(key, value, result.version)(storage)
    } */
  }
}