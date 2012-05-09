/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.zookeeper

import org.I0Itec.zkclient._
import org.apache.commons.io.FileUtils
import java.io.File
@deprecated("ZooKeeperBasedMailbox will be removed in Akka 2.1", "2.0.2")
object AkkaZooKeeper {
  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalServer(dataPath: String, logPath: String): ZkServer =
    startLocalServer(dataPath, logPath, 2181, 500)

  /**
   * Starts up a local ZooKeeper server. Should only be used for testing purposes.
   */
  def startLocalServer(dataPath: String, logPath: String, port: Int, tickTime: Int): ZkServer = {
    FileUtils.deleteDirectory(new File(dataPath))
    FileUtils.deleteDirectory(new File(logPath))
    val zkServer = new ZkServer(
      dataPath, logPath,
      new IDefaultNameSpace() {
        def createDefaultNameSpace(zkClient: ZkClient) {}
      },
      port, tickTime)
    zkServer.start()
    zkServer
  }
}
