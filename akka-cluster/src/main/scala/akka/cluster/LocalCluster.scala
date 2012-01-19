/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.config.Config
import Config._
import akka.util._
import Helpers._
import akka.actor._
import Actor._
import akka.event.EventHandler
import akka.cluster.zookeeper._

import org.apache.zookeeper._
import org.apache.zookeeper.Watcher.Event._
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.recipes.lock.{ WriteLock, LockListener }

import org.I0Itec.zkclient._
import org.I0Itec.zkclient.serialize._
import org.I0Itec.zkclient.exception._

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

object LocalCluster {
  val clusterDirectory = config.getString("akka.cluster.log-directory", "_akka_cluster")
  val clusterDataDirectory = clusterDirectory + "/data"
  val clusterLogDirectory = clusterDirectory + "/log"

  val clusterName = Config.clusterName
  val nodename = Config.nodename
  val zooKeeperServers = config.getString("akka.cluster.zookeeper-server-addresses", "localhost:2181")
  val sessionTimeout = Duration(config.getInt("akka.cluster.session-timeout", 60), TIME_UNIT).toMillis.toInt
  val connectionTimeout = Duration(config.getInt("akka.cluster.connection-timeout", 60), TIME_UNIT).toMillis.toInt
  val defaultZooKeeperSerializer = new SerializableSerializer

  val zkServer = new AtomicReference[Option[ZkServer]](None)

  lazy val zkClient = new AkkaZkClient(zooKeeperServers, sessionTimeout, connectionTimeout, defaultZooKeeperSerializer)

  /**
   * Looks up the local hostname.
   */
  def lookupLocalhostName = NetworkUtil.getLocalhostName

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(): ZkServer =
    startLocalCluster(clusterDataDirectory, clusterLogDirectory, 2181, 5000)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(port: Int, tickTime: Int): ZkServer =
    startLocalCluster(clusterDataDirectory, clusterLogDirectory, port, tickTime)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(tickTime: Int): ZkServer =
    startLocalCluster(clusterDataDirectory, clusterLogDirectory, 2181, tickTime)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(dataPath: String, logPath: String): ZkServer =
    startLocalCluster(dataPath, logPath, 2181, 500)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalCluster(dataPath: String, logPath: String, port: Int, tickTime: Int): ZkServer = {
    try {
      val zk = AkkaZooKeeper.startLocalServer(dataPath, logPath, port, tickTime)
      zkServer.set(Some(zk))
      zk
    } catch {
      case e: Throwable ⇒
        EventHandler.error(e, this, "Could not start local ZooKeeper cluster")
        throw e
    }
  }

  /**
   * Shut down the local ZooKeeper server.
   */
  def shutdownLocalCluster() {
    withPrintStackTraceOnError {
      EventHandler.debug(this, "Shuts down local cluster")
      zkServer.getAndSet(None).foreach(_.shutdown())
    }
  }

  def createQueue(rootPath: String, blocking: Boolean = true) =
    new ZooKeeperQueue(zkClient, rootPath, blocking)

  def barrier(name: String, count: Int): ZooKeeperBarrier =
    ZooKeeperBarrier(zkClient, clusterName, name, nodename, count)

  def barrier(name: String, count: Int, timeout: Duration): ZooKeeperBarrier =
    ZooKeeperBarrier(zkClient, clusterName, name, nodename, count, timeout)
}

