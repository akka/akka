/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.sample

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._

object SampleMultiJvmSpec {
  val NrOfNodes = 2
}

class SampleMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import SampleMultiJvmSpec._

  override def beforeAll() = {
    Cluster.startLocalCluster()
  }

  override def afterAll() = {
    Cluster.shutdownLocalCluster()
  }

  def resetCluster(): Unit = {
    import akka.cluster.zookeeper._
    import akka.util.Helpers.ignore
    import org.I0Itec.zkclient.exception.ZkNoNodeException
    val zkClient = Cluster.newZkClient
    ignore[ZkNoNodeException](zkClient.deleteRecursive("/" + Cluster.name))
    ignore[ZkNoNodeException](zkClient.deleteRecursive(ZooKeeperBarrier.BarriersNode))
    zkClient.close
  }

  "A cluster" must {

    "have jvm options" in {
      System.getProperty("akka.cluster.nodename", "") must be("node1")
      System.getProperty("akka.cluster.port", "") must be("9991")
      akka.config.Config.config.getString("test.name", "") must be("node1")
    }

    "be able to start all nodes" in {
      Cluster.barrier("start", NrOfNodes) {
        Cluster.node.start()
      }
      Cluster.node.isRunning must be(true)
      Cluster.node.shutdown()
    }
  }
}

class SampleMultiJvmNode2 extends WordSpec with MustMatchers {
  import SampleMultiJvmSpec._

  "A cluster" must {

    "have jvm options" in {
      System.getProperty("akka.cluster.nodename", "") must be("node2")
      System.getProperty("akka.cluster.port", "") must be("9992")
      akka.config.Config.config.getString("test.name", "") must be("node2")
    }

    "be able to start all nodes" in {
      Cluster.barrier("start", NrOfNodes) {
        Cluster.node.start()
      }
      Cluster.node.isRunning must be(true)
      Cluster.node.shutdown()
    }
  }
}
