package sample.distributeddata

import scala.concurrent.duration._
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.Replicator.GetReplicaCount
import akka.cluster.ddata.Replicator.ReplicaCount
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._

object ShoppingCartSpec extends MultiNodeConfig {
  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.log-dead-letters-during-shutdown = off
    """))

}

class ShoppingCartSpecMultiJvmNode1 extends ShoppingCartSpec
class ShoppingCartSpecMultiJvmNode2 extends ShoppingCartSpec
class ShoppingCartSpecMultiJvmNode3 extends ShoppingCartSpec

class ShoppingCartSpec extends MultiNodeSpec(ShoppingCartSpec) with STMultiNodeSpec with ImplicitSender {
  import ShoppingCartSpec._
  import ShoppingCart._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)
  val shoppingCart = system.actorOf(ShoppingCart.props("user-1"))

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Demo of a replicated shopping cart" must {
    "join cluster" in within(20.seconds) {
      join(node1, node1)
      join(node2, node1)
      join(node3, node1)

      awaitAssert {
        DistributedData(system).replicator ! GetReplicaCount
        expectMsg(ReplicaCount(roles.size))
      }
      enterBarrier("after-1")
    }

    "handle updates directly after start" in within(15.seconds) {
      runOn(node2) {
        shoppingCart ! new ShoppingCart.AddItem(new LineItem("1", "Apples", 2))
        shoppingCart ! new ShoppingCart.AddItem(new LineItem("2", "Oranges", 3))
      }
      enterBarrier("updates-done")

      awaitAssert {
        shoppingCart ! ShoppingCart.GET_CART
        val cart = expectMsgType[Cart]
        cart.items.asScala.toSet should be(Set(
            new LineItem("1", "Apples", 2), new LineItem("2", "Oranges", 3)))
      }

      enterBarrier("after-2")
    }

    "handle updates from different nodes" in within(5.seconds) {
      runOn(node2) {
        shoppingCart ! new ShoppingCart.AddItem(new LineItem("1", "Apples", 5))
        shoppingCart ! new ShoppingCart.RemoveItem("2")
      }
      runOn(node3) {
        shoppingCart ! new ShoppingCart.AddItem(new LineItem("3", "Bananas", 4))
      }
      enterBarrier("updates-done")

      awaitAssert {
        shoppingCart ! ShoppingCart.GET_CART
        val cart = expectMsgType[Cart]
        cart.items.asScala.toSet should be(
            Set(new LineItem("1", "Apples", 7), new LineItem("3", "Bananas", 4)))
      }

      enterBarrier("after-3")
    }

  }

}

