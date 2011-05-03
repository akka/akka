package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import org.I0Itec.zkclient._

import akka.actor._

class ClusterDeployerSpec extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {

  val dataPath = "_akka_cluster/data"
  val logPath  = "_akka_cluster/log"

  var zkServer: ZkServer = _

  "A ClusterDeployer" should {
    "be able to deploy deployments in configuration file" in {
      val deployments = Deployer.deploymentsInConfig
      deployments must not equal(Nil)
      ClusterDeployer.init(deployments)

      deployments map { oldDeployment =>
        val newDeployment = ClusterDeployer.lookupDeploymentFor(oldDeployment.address)
        newDeployment must be('defined)
        oldDeployment must equal(newDeployment.get)
      }
    }
  }

  override def beforeAll() {
    try {
      zkServer = Cluster.startLocalCluster(dataPath, logPath)
      Thread.sleep(5000)
    } catch {
      case e => e.printStackTrace()
    }
  }

  override def beforeEach() {
    Cluster.reset()
  }

  override def afterAll() {
    Deployer.shutdown()
    Cluster.shutdownLocalCluster()
    Actor.registry.local.shutdownAll()
  }
}
