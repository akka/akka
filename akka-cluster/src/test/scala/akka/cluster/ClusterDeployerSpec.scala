package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import org.I0Itec.zkclient._

import akka.actor._

object ClusterDeployerSpec {
  class Pi extends Actor {
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

  "A ClusterDeployer" should {
    "be able to deploy deployments in configuration file" in {
      val deployments = Deployer.deploymentsInConfig
      deployments must not equal (Nil)
      ClusterDeployer.init(deployments)

      deployments map { oldDeployment ⇒
        val newDeployment = ClusterDeployer.lookupDeploymentFor(oldDeployment.address)
        newDeployment must be('defined)
        oldDeployment must equal(newDeployment.get)
      }
    }

    /*
    "be able to create an actor deployed using ClusterDeployer" in {
      val pi = Actor.actorOf[Pi]("service-pi")
      pi must not equal(null)
    }
*/
  }

  override def beforeAll() {
    try {
      zkServer = Cluster.startLocalCluster(dataPath, logPath)
      Thread.sleep(5000)
    } catch {
      case e ⇒ e.printStackTrace()
    }
  }

  override def beforeEach() {
    Cluster.reset()
  }

  override def afterAll() {
    ClusterDeployer.shutdown()
    Cluster.shutdownLocalCluster()
    Actor.registry.local.shutdownAll()
  }
}
