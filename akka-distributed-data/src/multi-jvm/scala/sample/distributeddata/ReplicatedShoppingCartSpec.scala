/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.datareplication

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.Replicator.GetReplicaCount
import akka.cluster.ddata.Replicator.ReplicaCount
import akka.cluster.ddata.STMultiNodeSpec
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.cluster.ddata.LWWMapKey

object ReplicatedShoppingCartSpec extends MultiNodeConfig {
  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.log-dead-letters-during-shutdown = off
    """))

}

object ShoppingCart {
  import akka.cluster.ddata.Replicator._

  def props(userId: String): Props = Props(new ShoppingCart(userId))

  case object GetCart
  final case class AddItem(item: LineItem)
  final case class RemoveItem(productId: String)

  final case class Cart(items: Set[LineItem])
  final case class LineItem(productId: String, title: String, quantity: Int)

  //#read-write-majority
  private val timeout = 3.seconds
  private val readMajority = ReadMajority(timeout)
  private val writeMajority = WriteMajority(timeout)
  //#read-write-majority

}

class ShoppingCart(userId: String) extends Actor {
  import ShoppingCart._
  import akka.cluster.ddata.Replicator._

  val replicator = DistributedData(context.system).replicator
  implicit val cluster = Cluster(context.system)

  val DataKey = LWWMapKey[LineItem]("cart-" + userId)

  def receive = receiveGetCart
    .orElse[Any, Unit](receiveAddItem)
    .orElse[Any, Unit](receiveRemoveItem)
    .orElse[Any, Unit](receiveOther)

  //#get-cart
  def receiveGetCart: Receive = {
    case GetCart ⇒
      replicator ! Get(DataKey, readMajority, Some(sender()))

    case g @ GetSuccess(DataKey, Some(replyTo: ActorRef)) ⇒
      val data = g.get(DataKey)
      val cart = Cart(data.entries.values.toSet)
      replyTo ! cart

    case NotFound(DataKey, Some(replyTo: ActorRef)) ⇒
      replyTo ! Cart(Set.empty)

    case GetFailure(DataKey, Some(replyTo: ActorRef)) ⇒
      // ReadMajority failure, try again with local read
      replicator ! Get(DataKey, ReadLocal, Some(replyTo))
  }
  //#get-cart

  //#add-item
  def receiveAddItem: Receive = {
    case cmd @ AddItem(item) ⇒
      val update = Update(DataKey, LWWMap.empty[LineItem], writeMajority, Some(cmd)) {
        cart ⇒ updateCart(cart, item)
      }
      replicator ! update

    case GetFailure(DataKey, Some(AddItem(item))) ⇒
      // ReadMajority of Update failed, fall back to best effort local value
      replicator ! Update(DataKey, LWWMap.empty[LineItem], writeMajority, None) {
        cart ⇒ updateCart(cart, item)
      }
  }
  //#add-item

  //#remove-item
  def receiveRemoveItem: Receive = {
    case cmd @ RemoveItem(productId) ⇒
      // Try to fetch latest from a majority of nodes first, since ORMap
      // remove must have seen the item to be able to remove it.
      replicator ! Get(DataKey, readMajority, Some(cmd))

    case GetSuccess(DataKey, Some(RemoveItem(productId))) ⇒
      replicator ! Update(DataKey, LWWMap(), writeMajority, None) {
        _ - productId
      }

    case GetFailure(DataKey, Some(RemoveItem(productId))) ⇒
      // ReadMajority failed, fall back to best effort local value
      replicator ! Update(DataKey, LWWMap(), writeMajority, None) {
        _ - productId
      }

    case NotFound(DataKey, Some(RemoveItem(productId))) ⇒
    // nothing to remove
  }
  //#remove-item

  def receiveOther: Receive = {
    case _: UpdateSuccess[_] | _: UpdateTimeout[_] ⇒
    // UpdateTimeout, will eventually be replicated
    case e: UpdateFailure[_]                       ⇒ throw new IllegalStateException("Unexpected failure: " + e)
  }

  def updateCart(data: LWWMap[LineItem], item: LineItem): LWWMap[LineItem] =
    data.get(item.productId) match {
      case Some(LineItem(_, _, existingQuantity)) ⇒
        data + (item.productId -> item.copy(quantity = existingQuantity + item.quantity))
      case None ⇒ data + (item.productId -> item)
    }

}

class ReplicatedShoppingCartSpecMultiJvmNode1 extends ReplicatedShoppingCartSpec
class ReplicatedShoppingCartSpecMultiJvmNode2 extends ReplicatedShoppingCartSpec
class ReplicatedShoppingCartSpecMultiJvmNode3 extends ReplicatedShoppingCartSpec

class ReplicatedShoppingCartSpec extends MultiNodeSpec(ReplicatedShoppingCartSpec) with STMultiNodeSpec with ImplicitSender {
  import ReplicatedShoppingCartSpec._
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
    "join cluster" in within(10.seconds) {
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
        shoppingCart ! ShoppingCart.AddItem(LineItem("1", "Apples", quantity = 2))
        shoppingCart ! ShoppingCart.AddItem(LineItem("2", "Oranges", quantity = 3))
      }
      enterBarrier("updates-done")

      awaitAssert {
        shoppingCart ! ShoppingCart.GetCart
        val cart = expectMsgType[Cart]
        cart.items should be(Set(LineItem("1", "Apples", quantity = 2), LineItem("2", "Oranges", quantity = 3)))
      }

      enterBarrier("after-2")
    }

    "handle updates from different nodes" in within(5.seconds) {
      runOn(node2) {
        shoppingCart ! ShoppingCart.AddItem(LineItem("1", "Apples", quantity = 5))
        shoppingCart ! ShoppingCart.RemoveItem("2")
      }
      runOn(node3) {
        shoppingCart ! ShoppingCart.AddItem(LineItem("3", "Bananas", quantity = 4))
      }
      enterBarrier("updates-done")

      awaitAssert {
        shoppingCart ! ShoppingCart.GetCart
        val cart = expectMsgType[Cart]
        cart.items should be(Set(LineItem("1", "Apples", quantity = 7), LineItem("3", "Bananas", quantity = 4)))
      }

      enterBarrier("after-3")
    }

  }

}

