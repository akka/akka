package akka.actor.mailbox

import akka.actor.Actor
import akka.cluster.zookeeper._
import org.I0Itec.zkclient._
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import akka.util.duration._
import akka.dispatch.{ Mailbox, MessageDispatcher }

object ZooKeeperBasedMailboxSpec {
  val config = """
    ZooKeeper-dispatcher {
      mailbox-type = akka.actor.mailbox.ZooKeeperBasedMailboxType
      throughput = 1
      zookeeper.session-timeout = 30s
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ZooKeeperBasedMailboxSpec extends DurableMailboxSpec("ZooKeeper", ZooKeeperBasedMailboxSpec.config) {

  val dataPath = "_akka_cluster/data"
  val logPath = "_akka_cluster/log"

  "ZookeeperBasedMailboxSettings" must {
    "read the right settings" in {
      new ZooKeeperBasedMailboxSettings(system.settings, system.settings.config.getConfig("ZooKeeper-dispatcher")).SessionTimeout must be(30 seconds)
    }
  }

  var zkServer: ZkServer = _
  def isDurableMailbox(m: Mailbox): Boolean = m.messageQueue.isInstanceOf[ZooKeeperBasedMessageQueue]
  override def atStartup() {
    zkServer = AkkaZooKeeper.startLocalServer(dataPath, logPath)
    super.atStartup()
  }

  override def atTermination() {
    zkServer.shutdown()
    super.atTermination()
  }
}
