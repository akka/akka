/**
 *  Copyright (C) 2011-2012 Typesafe <http://typesafe.com/>
 */
package akka.remote

import org.apache.zookeeper._
import ZooDefs.Ids

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

    val timeoutMs = 300*1000

    private def block(num: Int) {
      val start = System.currentTimeMillis
      while (true) {
        if (System.currentTimeMillis - start > timeoutMs)
          throw new InterruptedException("Timed out blocking in zk")

        ZkClient.this.synchronized {
          val children = zk.getChildren(root, true)
          if (children.size < num) {
            ZkClient.this.wait(timeoutMs)
          } else
            return
        }
      }
    }

    def enter() {
      zk.create(root + "/" + name, Array[Byte](), Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL)

      block(count)
    }

    final def leave() {
      zk.create(root + "/" + name + ".leave", Array[Byte](), Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL)

      block(2*count)
    }
  }

  def barrier(name: String, count: Int, root: String) = new ZkBarrier(name, count, root)
}
