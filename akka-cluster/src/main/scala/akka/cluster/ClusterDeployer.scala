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

  def lookupDeploymentFor(address: String): Option[Deploy] = ensureRunning {
    LocalDeployer.lookupDeploymentFor(address) match { // try local cache
      case Some(deployment) ⇒ // in local cache
        deployment
      case None ⇒ // not in cache, check cluster
        val deployment =
          try {
            Some(zkClient.readData(deploymentAddressPath.format(address)).asInstanceOf[Deploy])
          } catch {
            case e: ZkNoNodeException ⇒ None
            case e: Exception ⇒
              EventHandler.warning(this, e.toString)
              None
          }
        deployment foreach (LocalDeployer.deploy(_)) // cache it in local cache
        deployment
    }
  }

  def fetchDeploymentsFromCluster: List[Deploy] = ensureRunning {
    val addresses =
      try {
        zkClient.getChildren(deploymentPath).toList
      } catch {
        case e: ZkNoNodeException ⇒ List[String]()
      }
    val deployments = addresses map { address ⇒
      zkClient.readData(deploymentAddressPath.format(address)).asInstanceOf[Deploy]
    }
    EventHandler.info(this, "Fetched clustered deployments [\n\t%s\n]" format deployments.mkString("\n\t"))
    deployments
  }

  private[akka] def init(deployments: List[Deploy]) {
    println("===============================================================")
    println("------------ INIT 1")
    isConnected switchOn {
      EventHandler.info(this, "Initializing cluster deployer")

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

      println("------------ INIT 2")
      val allDeployments = deployments ::: systemDeployments

      // FIXME need to wrap in if (!deploymentDone) { .. }

      if (deploymentLock.lock()) {
        println("------------ INIT 3")
        // try to be the one doing the clustered deployment
        EventHandler.info(this, "Deploying to cluster [\n" + allDeployments.mkString("\n\t") + "\n]")

        println("------------ INIT 4")
        allDeployments foreach (deploy(_)) // deploy
        println("------------ INIT 5")

        // FIXME need to set deployment done flag

        deploymentLock.unlock() // signal deployment complete
      } else {
        println("------------ INIT WAITING")
        deploymentCompleted.await() // wait until deployment is completed by other "master" node
      }

      println("------------ INIT 6")
      // fetch clustered deployments and deploy them locally
      fetchDeploymentsFromCluster foreach (LocalDeployer.deploy(_))
    }
  }

  private[akka] def deploy(deployment: Deploy) {
    ensureRunning {
      LocalDeployer.deploy(deployment)
      deployment match {
        case Deploy(_, _, _, Local) ⇒ {} // local deployment, do nothing here
        case _ ⇒ // cluster deployment
          val path = deploymentAddressPath.format(deployment.address)
          try {
            ignore[ZkNodeExistsException](zkClient.create(path, null, CreateMode.PERSISTENT))
            zkClient.writeData(path, deployment)
          } catch {
            case e: NullPointerException ⇒
              handleError(new DeploymentException("Could not store deployment data [" + deployment + "] in ZooKeeper since client session is closed"))
            case e: Exception ⇒
              handleError(new DeploymentException("Could not store deployment data [" + deployment + "] in ZooKeeper due to: " + e))
          }
      }
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
