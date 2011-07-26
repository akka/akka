package akka.actor.mailbox

import akka.actor.Actor
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
    Actor.registry.local.actors.foreach(_.mailbox match {
      case zkm: ZooKeeperBasedMailbox ⇒ zkm.close
      case _                          ⇒ ()
    })
    super.afterEach
  }

  override def afterAll() {
    zkServer.shutdown
    super.afterAll
  }
}
