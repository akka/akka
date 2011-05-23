/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster

import akka.actor.{ DeploymentConfig, Deployer, LocalDeployer, DeploymentException }
import DeploymentConfig._
import akka.event.EventHandler
import akka.config.Config
import akka.util.Switch
import akka.util.Helpers._
import akka.cluster.zookeeper.AkkaZkClient

import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.recipes.lock.{ WriteLock, LockListener }

import org.I0Itec.zkclient.exception.{ ZkNoNodeException, ZkNodeExistsException }

import scala.collection.JavaConversions.collectionAsScalaIterable

import com.eaio.uuid.UUID

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * A ClusterDeployer is responsible for deploying a Deploy.
 *
 * big question is: what does Deploy mean?
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ClusterDeployer {
  val clusterName = Cluster.name
  val nodeName = Config.nodename
  val clusterPath = "/%s" format clusterName
  val clusterDeploymentLockPath = clusterPath + "/deployment-lock"
  val deploymentPath = clusterPath + "/deployment"
  val baseNodes = List(clusterPath, clusterDeploymentLockPath, deploymentPath)
  val deploymentAddressPath = deploymentPath + "/%s"

  private val isConnected = new Switch(false)
  private val deploymentCompleted = new CountDownLatch(1)

  private val zkClient = new AkkaZkClient(
    Cluster.zooKeeperServers,
    Cluster.sessionTimeout,
    Cluster.connectionTimeout,
    Cluster.defaultSerializer)

  private val clusterDeploymentLockListener = new LockListener {
    def lockAcquired() {
      EventHandler.debug(this, "Clustered deployment started")
    }

    def lockReleased() {
      EventHandler.debug(this, "Clustered deployment completed")
      deploymentCompleted.countDown()
    }
  }

  private val deploymentLock = new WriteLock(
    zkClient.connection.getZookeeper, clusterDeploymentLockPath, null, clusterDeploymentLockListener) {
    private val ownerIdField = classOf[WriteLock].getDeclaredField("ownerId")
    ownerIdField.setAccessible(true)

    def leader: String = ownerIdField.get(this).asInstanceOf[String]
  }

  private val systemDeployments: List[Deploy] = Nil

  private[akka] def init(deployments: List[Deploy]) {
    isConnected switchOn {
      baseNodes foreach { path ⇒
        try {
          ignore[ZkNodeExistsException](zkClient.create(path, null, CreateMode.PERSISTENT))
          EventHandler.debug(this, "Created node [%s]".format(path))
        } catch {
          case e ⇒
            val error = new DeploymentException(e.toString)
            EventHandler.error(error, this)
            throw error
        }
      }

      val allDeployments = deployments ::: systemDeployments
      EventHandler.info(this, "Initializing cluster deployer")
      if (deploymentLock.lock()) {
        // try to be the one doing the clustered deployment
        EventHandler.info(this, "Deploying to cluster [\n" + allDeployments.mkString("\n\t") + "\n]")
        allDeployments foreach (deploy(_)) // deploy
        deploymentLock.unlock() // signal deployment complete
      } else {
        deploymentCompleted.await() // wait until deployment is completed
      }
    }
  }

  def shutdown() {
    isConnected switchOff {
      // undeploy all
      try {
        for {
          child ← collectionAsScalaIterable(zkClient.getChildren(deploymentPath))
          deployment ← zkClient.readData(deploymentAddressPath.format(child)).asInstanceOf[Deploy]
        } zkClient.delete(deploymentAddressPath.format(deployment.address))

      } catch {
        case e: Exception ⇒
          handleError(new DeploymentException("Could not undeploy all deployment data in ZooKeeper due to: " + e))
      }

      // shut down ZooKeeper client
      zkClient.close()
      EventHandler.info(this, "ClusterDeployer shut down successfully")
    }
  }

  private[akka] def deploy(deployment: Deploy) {
    ensureRunning {
      deployment match {
        case Deploy(_, _, _, Local) ⇒ // local deployment
          LocalDeployer.deploy(deployment)

        case _ ⇒ // cluster deployment
          val path = deploymentAddressPath.format(deployment.address)
          try {
            ignore[ZkNodeExistsException](zkClient.create(path, null, CreateMode.PERSISTENT))
            zkClient.writeData(path, deployment)

            // FIXME trigger cluster-wide deploy action
          } catch {
            case e: NullPointerException ⇒
              handleError(new DeploymentException("Could not store deployment data [" + deployment + "] in ZooKeeper since client session is closed"))
            case e: Exception ⇒
              handleError(new DeploymentException("Could not store deployment data [" + deployment + "] in ZooKeeper due to: " + e))
          }
      }
    }
  }

  private[akka] def lookupDeploymentFor(address: String): Option[Deploy] = ensureRunning {
    try {
      Some(zkClient.readData(deploymentAddressPath.format(address)).asInstanceOf[Deploy])
    } catch {
      case e: ZkNoNodeException ⇒ None
      case e: Exception ⇒
        EventHandler.warning(this, e.toString)
        None
    }
  }

  private def ensureRunning[T](body: ⇒ T): T = {
    if (isConnected.isOn) body
    else throw new IllegalStateException("ClusterDeployer is not running")
  }

  private[akka] def handleError(e: Throwable): Nothing = {
    EventHandler.error(e, this, e.toString)
    throw e
  }
}
