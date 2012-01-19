/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.DeploymentConfig._
import akka.actor._
import akka.event.EventHandler
import akka.config.Config
import akka.util.Switch
import akka.util.Helpers._
import akka.cluster.zookeeper.AkkaZkClient

import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.recipes.lock.{ WriteLock, LockListener }

import org.I0Itec.zkclient.exception.{ ZkNoNodeException, ZkNodeExistsException }

import scala.collection.immutable.Seq
import scala.collection.JavaConversions.collectionAsScalaIterable

import java.util.concurrent.{ CountDownLatch, TimeUnit }

/**
 * A ClusterDeployer is responsible for deploying a Deploy.
 */
object ClusterDeployer extends ActorDeployer {
  val clusterName = Cluster.name
  val nodeName = Config.nodename
  val clusterPath = "/%s" format clusterName

  val deploymentPath = clusterPath + "/deployment"
  val deploymentAddressPath = deploymentPath + "/%s"

  val deploymentCoordinationPath = clusterPath + "/deployment-coordination"
  val deploymentInProgressLockPath = deploymentCoordinationPath + "/in-progress"
  val isDeploymentCompletedInClusterLockPath = deploymentCoordinationPath + "/completed" // should not be part of basePaths

  val basePaths = List(clusterPath, deploymentPath, deploymentCoordinationPath, deploymentInProgressLockPath)

  private val isConnected = new Switch(false)
  private val deploymentCompleted = new CountDownLatch(1)

  private val zkClient = new AkkaZkClient(
    Cluster.zooKeeperServers,
    Cluster.sessionTimeout,
    Cluster.connectionTimeout,
    Cluster.defaultZooKeeperSerializer)

  private val deploymentInProgressLockListener = new LockListener {
    def lockAcquired() {
      EventHandler.info(this, "Clustered deployment started")
    }

    def lockReleased() {
      EventHandler.info(this, "Clustered deployment completed")
      deploymentCompleted.countDown()
    }
  }

  private val deploymentInProgressLock = new WriteLock(
    zkClient.connection.getZookeeper,
    deploymentInProgressLockPath,
    null,
    deploymentInProgressLockListener)

  private val systemDeployments: List[Deploy] = Nil

  def shutdown() {
    isConnected switchOff {
      // undeploy all
      try {
        for {
          child ← collectionAsScalaIterable(zkClient.getChildren(deploymentPath))
          deployment ← zkClient.readData(deploymentAddressPath.format(child)).asInstanceOf[Deploy]
        } zkClient.delete(deploymentAddressPath.format(deployment.address))

        invalidateDeploymentInCluster()
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
    EventHandler.info(this, "Fetched deployment plans from cluster [\n\t%s\n]" format deployments.mkString("\n\t"))
    deployments
  }

  private[akka] def init(deployments: Seq[Deploy]) {
    isConnected switchOn {
      EventHandler.info(this, "Initializing ClusterDeployer")

      basePaths foreach { path ⇒
        try {
          ignore[ZkNodeExistsException](zkClient.create(path, null, CreateMode.PERSISTENT))
          EventHandler.debug(this, "Created ZooKeeper path for deployment [%s]".format(path))
        } catch {
          case e ⇒
            val error = new DeploymentException(e.toString)
            EventHandler.error(error, this)
            throw error
        }
      }

      val allDeployments = deployments ++ systemDeployments

      if (!isDeploymentCompletedInCluster) {
        if (deploymentInProgressLock.lock()) {
          // try to be the one doing the clustered deployment
          EventHandler.info(this, "Pushing clustered deployment plans [\n\t" + allDeployments.mkString("\n\t") + "\n]")
          allDeployments foreach (deploy(_)) // deploy
          markDeploymentCompletedInCluster()
          deploymentInProgressLock.unlock() // signal deployment complete

        } else {
          deploymentCompleted.await(30, TimeUnit.SECONDS) // wait until deployment is completed by other "master" node
        }
      }

      // fetch clustered deployments and deploy them locally
      fetchDeploymentsFromCluster foreach (LocalDeployer.deploy(_))
    }
  }

  private[akka] def deploy(deployment: Deploy) {
    ensureRunning {
      LocalDeployer.deploy(deployment)
      deployment match {
        case Deploy(_, _, _, _, Local) | Deploy(_, _, _, _, _: Local) ⇒ //TODO LocalDeployer.deploy(deployment)??
        case Deploy(address, recipe, routing, _, _) ⇒ // cluster deployment
          /*TODO recipe foreach { r ⇒
            Deployer.newClusterActorRef(() ⇒ Actor.actorOf(r.implementationClass), address, deployment)
          }*/
          val path = deploymentAddressPath.format(address)
          try {
            ignore[ZkNodeExistsException](zkClient.create(path, null, CreateMode.PERSISTENT))
            zkClient.writeData(path, deployment)
          } catch {
            case e: NullPointerException ⇒
              handleError(new DeploymentException(
                "Could not store deployment data [" + deployment + "] in ZooKeeper since client session is closed"))
            case e: Exception ⇒
              handleError(new DeploymentException(
                "Could not store deployment data [" + deployment + "] in ZooKeeper due to: " + e))
          }
      }
    }
  }

  private def markDeploymentCompletedInCluster() {
    ignore[ZkNodeExistsException](zkClient.create(isDeploymentCompletedInClusterLockPath, null, CreateMode.PERSISTENT))
  }

  private def isDeploymentCompletedInCluster = zkClient.exists(isDeploymentCompletedInClusterLockPath)

  // FIXME in future - add watch to this path to be able to trigger redeployment, and use this method to trigger redeployment
  private def invalidateDeploymentInCluster() {
    ignore[ZkNoNodeException](zkClient.delete(isDeploymentCompletedInClusterLockPath))
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
