/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster

import akka.actor.DeploymentConfig.Deploy
import akka.actor.DeploymentException
import akka.event.EventHandler
import akka.util.Switch
import akka.cluster.zookeeper.AkkaZkClient

import org.apache.zookeeper.CreateMode

import scala.collection.JavaConversions.collectionAsScalaIterable

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ClusterDeployer {
  val deploymentPath        = "deployment"
  val deploymentAddressPath = deploymentPath + "/%s"

  private val isConnected = new Switch(false)

  private lazy val zkClient = {
    val zk = new AkkaZkClient(
      Cluster.zooKeeperServers,
      Cluster.sessionTimeout,
      Cluster.connectionTimeout,
      Cluster.defaultSerializer)
    EventHandler.info(this, "ClusterDeployer started")
    isConnected.switchOn
    zk
  }

  def shutdown() {
    isConnected switchOff {
      zkClient.close()
    }
  }

  def deploy(deployment: Deploy) {
    try {
      val path  = deploymentAddressPath.format(deployment.address)
      zkClient.create(path, null, CreateMode.PERSISTENT)
      zkClient.writeData(path, deployment)

      // FIXME trigger some deploy action?
    } catch {
      case e => handleError(new DeploymentException("Could store deployment data [" + deployment + "] in ZooKeeper due to: " + e))
    }
  }

  def undeploy(deployment: Deploy) {
    try {
      zkClient.delete(deploymentAddressPath.format(deployment.address))

      // FIXME trigger some undeploy action?
    } catch {
      case e => handleError(new DeploymentException("Could undeploy deployment [" + deployment + "] in ZooKeeper due to: " + e))
    }
  }

  def undeployAll() {
    try {
      for {
        child      <- collectionAsScalaIterable(zkClient.getChildren(deploymentPath))
        deployment <- lookupDeploymentFor(child)
      } undeploy(deployment)
    } catch {
      case e => handleError(new DeploymentException("Could undeploy all deployment data in ZooKeeper due to: " + e))
    }
  }

  def lookupDeploymentFor(address: String): Option[Deploy] = {
    try {
      Some(zkClient.readData(deploymentAddressPath.format(address)).asInstanceOf[Deploy])
    } catch {
      case e: Exception => None
    }
  }

  private[akka] def handleError(e: Throwable): Nothing = {
    EventHandler.error(e, this, e.toString)
    throw e
  }
}
