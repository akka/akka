/**
 *  Copyright (C) 2011 Typesafe <http://typesafe.com/>
 */
package akka.remote

import com.typesafe.config.Config
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
    if (zk.exists(root, false) eq null) {
      zk.create(root, Array[Byte](), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
    }

    def enter() {
      zk.create(root + "/" + name, Array[Byte](), Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL)
      while (true) {
        ZkClient.this.synchronized {
          if (zk.getChildren(root, true).size < count) {
            ZkClient.this.wait()
          }
        }
      }
    }

    def leave() {
      zk.delete(root + "/" + name, -1)
      while (true) {
        ZkClient.this.synchronized {
          if (!zk.getChildren(root, true).isEmpty) {
            ZkClient.this.wait()
          }
        }
      }
    }
  }

  def barrier(name: String, count: Int, root: String) = new ZkBarrier(name, count, root)
}
