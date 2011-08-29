package akka.actor.mailbox

import akka.actor.{ Actor, LocalActorRef }
import akka.cluster.zookeeper._

import org.I0Itec.zkclient._

class ZooKeeperBasedMailboxSpec extends DurableMailboxSpec("ZooKeeper", ZooKeeperDurableMailboxStorage) {
  val dataPath = "_akka_cluster/data"
  val logPath = "_akka_cluster/log"

  var zkServer: ZkServer = _

  override def beforeAll() {
    zkServer = AkkaZooKeeper.startLocalServer(dataPath, logPath)
    super.beforeAll
  }

  override def afterEach() {
    Actor.registry.local.actors foreach {
      case l: LocalActorRef ⇒ l.mailbox match {
        case zk: ZooKeeperBasedMailbox ⇒ zk.close()
        case _                         ⇒
      }
    }
    super.afterEach
  }

  override def afterAll() {
    zkServer.shutdown
    super.afterAll
  }
}
