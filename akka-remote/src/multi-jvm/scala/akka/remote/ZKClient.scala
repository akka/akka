/**
 *  Copyright (C) 2011 Typesafe <http://typesafe.com/>
 */
package akka.remote

import com.typesafe.config.Config
import org.apache.zookeeper._
import ZooDefs.Ids

class ZKClient(config: Config) extends Watcher {
  // Don't forget to close!
  lazy val zk: ZooKeeper = {
    val remoteNodes = config.getString("akka.test.remote.nodes") split ',' map {
      case hostport => hostport.split(":")(0)
    }
  
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
        ZKClient.this.synchronized {
          if (zk.getChildren(root, true).size < count) {
            ZKClient.this.wait()
          }
        }
      }
    }

    def leave() {
      zk.delete(root + "/" + name, -1)
      while (true) {
        ZKClient.this.synchronized {
          if (zk.getChildren(root, true).size > 0) {
            ZKClient.this.wait()
          }
        }
      }
    }
  }

  def barrier(name: String, count: Int, root: String) = new ZkBarrier(name, count, root)
}
