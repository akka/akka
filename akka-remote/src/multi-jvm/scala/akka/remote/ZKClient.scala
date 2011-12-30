/**
 *  Copyright (C) 2011 Typesafe <http://typesafe.com/>
 */
package akka.remote

import com.typesafe.config.Config
import org.apache.zookeeper._
import ZooDefs.Ids
import collection.JavaConversions._
import java.net.InetAddress

object ZkClient extends Watcher {
  // Don't forget to close!
  lazy val zk: ZooKeeper = {
    val remoteNodes = AkkaRemoteSpec.testNodes split ','

    // ZkServers are configured to listen on a specific port.
    val connectString = remoteNodes map (_+":2181") mkString ","
    new ZooKeeper(connectString, 3000, this)
  }

  def process(ev: WatchedEvent) {
    synchronized { notify() }
  }

  class ZkBarrier(name: String, count: Int, root: String) extends Barrier {
    @annotation.tailrec
    private def waitForServer() {
      // SI-1672
      val r = try {
        zk.exists("/", false); true
      } catch {
        case _: KeeperException.ConnectionLossException =>
          println("Server is not ready, sleeping...")
          Thread.sleep(10000)
          false
      }
      if (!r) waitForServer()
    }
    waitForServer()

    try {
      zk.create(root, Array[Byte](), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
    } catch {
      case _: KeeperException.NodeExistsException =>
    }

    def enter() {
      zk.create(root + "/" + name, Array[Byte](), Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL)

      while (true) {
        ZkClient.this.synchronized {
          val children = zk.getChildren(root, true)
          println("Enter, children: " + children.mkString(","))
          if (children.size < count) {
            println("waiting")
            ZkClient.this.wait()
          } else
            return
        }
      }
    }

    final def leave() {
      zk.delete(root + "/" + name, -1)

      while (true) {
        ZkClient.this.synchronized {
          val children = zk.getChildren(root, true)
          println("Leave, children: " + children.mkString(","))
          if (!children.isEmpty) {
            println("waiting")
            ZkClient.this.wait()
          } else
            return
        }
      }
    }
  }

  def barrier(name: String, count: Int, root: String) = new ZkBarrier(name, count, root)
}
