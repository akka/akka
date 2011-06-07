package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import org.I0Itec.zkclient._

import akka.actor._
import Actor._

object ClusterDeployerSpec {
  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒ self.reply("World")
    }
  }
}

class ClusterDeployerSpec extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import ClusterDeployerSpec._

  val dataPath = "_akka_cluster/data"
  val logPath = "_akka_cluster/log"

  var zkServer: ZkServer = _

  // FIXME create multi-jvm test for ClusterDeployer to make sure that only one node can make the deployment and that all other nicely waits until he is done

  "A ClusterDeployer" should {
    "be able to deploy deployments in akka.conf into ZooKeeper and then lookup the deployments by 'address'" in {
      val deployments = Deployer.deploymentsInConfig
      deployments must not equal (Nil)
      ClusterDeployer.init(deployments)

      deployments map { oldDeployment ⇒
        val newDeployment = ClusterDeployer.lookupDeploymentFor(oldDeployment.address)
        newDeployment must be('defined)
        oldDeployment must equal(newDeployment.get)
      }
    }

    "be able to fetch deployments from ZooKeeper" in {
      val deployments1 = Deployer.deploymentsInConfig
      deployments1 must not equal (Nil)
      ClusterDeployer.init(deployments1)

      val deployments2 = ClusterDeployer.fetchDeploymentsFromCluster
      deployments2.size must equal(1)
      deployments2.head must equal(deployments1.head)
    }
  }

  override def beforeAll() {
    try {
      zkServer = Cluster.startLocalCluster(dataPath, logPath)
      Thread.sleep(5000)
      Actor.cluster.start()
    } catch {
      case e ⇒ e.printStackTrace()
    }
  }

  override def afterAll() {
    Actor.cluster.shutdown()
    ClusterDeployer.shutdown()
    Cluster.shutdownLocalCluster()
    Actor.registry.local.shutdownAll()
  }
}
