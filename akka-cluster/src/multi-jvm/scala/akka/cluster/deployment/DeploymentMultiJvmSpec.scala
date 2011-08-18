/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.deployment

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.actor._
import Actor._
import akka.cluster._
import Cluster._

object DeploymentMultiJvmSpec {
  var NrOfNodes = 2
}

class DeploymentMultiJvmNode1 extends MasterClusterTestNode {
  import DeploymentMultiJvmSpec._

  val testNodes = NrOfNodes

  "A ClusterDeployer" must {

    "be able to deploy deployments in akka.conf and lookup the deployments by 'address'" in {

      barrier("start-node-1", NrOfNodes) {
        Cluster.node
      }

      barrier("start-node-2", NrOfNodes) {
      }

      barrier("perform-deployment-on-node-1", NrOfNodes) {
        Deployer.start()
      }

      barrier("lookup-deployment-node-2", NrOfNodes) {
      }

      node.shutdown()
    }
  }
}

class DeploymentMultiJvmNode2 extends ClusterTestNode {
  import DeploymentMultiJvmSpec._

  "A cluster" must {

    "be able to store, read and remove custom configuration data" in {

      barrier("start-node-1", NrOfNodes) {
      }

      barrier("start-node-2", NrOfNodes) {
        Cluster.node
      }

      barrier("perform-deployment-on-node-1", NrOfNodes) {
      }

      barrier("lookup-deployment-node-2", NrOfNodes) {
        Deployer.start()
        val deployments = Deployer.deploymentsInConfig
        deployments map { oldDeployment ⇒
          val newDeployment = ClusterDeployer.lookupDeploymentFor(oldDeployment.address)
          newDeployment must be('defined)
          oldDeployment must equal(newDeployment.get)
        }
      }

      node.shutdown()
    }
  }
}
