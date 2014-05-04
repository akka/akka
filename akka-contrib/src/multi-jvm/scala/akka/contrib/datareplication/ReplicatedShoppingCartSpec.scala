/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Stash
import akka.cluster.Cluster
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._

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

  def props(userId: String): Props = Props(new ShoppingCart(userId))

  case object GetCart
  case class AddItem(item: LineItem)
  case class RemoveItem(productId: String)

  case class Cart(items: Set[LineItem])
  case class LineItem(productId: String, title: String, quantity: Int)

}

class ShoppingCart(userId: String) extends Actor with Stash {
  import ShoppingCart._
  import akka.contrib.datareplication.Replicator._

  val replicator = DataReplication(context.system).replicator
  implicit val cluster = Cluster(context.system)
  val timeout = 3.seconds
  val DataKey = "cart-" + userId

  def receive = ready

  // because of the get-modify-update roundtrip it will
  // process one request at a time to support reading own updates,
  // and make sure that own updates are not re-ordered
  val ready: Receive = {
    case AddItem(item) ⇒
      replicator ! Get(DataKey, ReadQuorum, timeout)
      context.become(addItemInProgress(item))

    case GetCart ⇒
      replicator ! Get(DataKey, ReadQuorum, timeout)
      context.become(getCartInProgress(sender()))

    case RemoveItem(productId) ⇒
      replicator ! Get(DataKey, ReadQuorum, timeout)
      context.become(removeItemInProgress(productId))
  }

  def becomeReady(): Unit = {
    unstashAll()
    context.become(ready)
  }

  def addItemInProgress(item: LineItem): Receive = {
    case GetSuccess(_, data: LWWMap, seqNo, _) ⇒
      val newData = update(data, item)
      replicator ! Update(DataKey, newData, seqNo, WriteQuorum, timeout)

    case _: NotFound ⇒
      val data = LWWMap() :+ (item.productId -> item)
      replicator ! Update(DataKey, data, 0, WriteQuorum, timeout)

    case _: GetFailure ⇒
      // ReadQuorum failure, try again with local read
      replicator ! Get(DataKey, ReadOne, timeout)

    case WrongSeqNo(_, data: LWWMap, seqNo, currentSeqNo, _) ⇒
      // try again with currentSeqNo
      val newData = update(data, item)
      replicator ! Update(DataKey, newData, currentSeqNo, WriteQuorum, timeout)

    case _: UpdateSuccess | _: ReplicationUpdateFailure ⇒
      // ReplicationUpdateFailure, will eventually be replicated
      becomeReady()

    case _ ⇒ stash()
  }

  def getCartInProgress(replyTo: ActorRef): Receive = {

    case GetSuccess(_, data: LWWMap, _, _) ⇒
      val cart = Cart(data.entries.values.map { case line: LineItem ⇒ line }.toSet)
      replyTo ! cart
      becomeReady()

    case _: NotFound ⇒
      replyTo ! Cart(Set.empty)
      becomeReady()

    case _: GetFailure ⇒
      // ReadQuorum failure, try again with local read
      replicator ! Get(DataKey, ReadOne, timeout)
  }

  def removeItemInProgress(productId: String): Receive = {

    case GetSuccess(_, data: LWWMap, seqNo, _) ⇒
      val newData = data :- productId
      replicator ! Update(DataKey, newData, seqNo, WriteQuorum, timeout)

    case _: GetFailure ⇒
      // ReadQuorum failure, try again with local read
      replicator ! Get(DataKey, ReadOne, timeout)

    case _: NotFound ⇒
    // ok, cart not replicated yet, not possible to remove item

    case WrongSeqNo(_, data: LWWMap, seqNo, currentSeqNo, _) ⇒
      // try again with currentSeqNo
      val newData = data :- productId
      replicator ! Update(DataKey, newData, currentSeqNo, WriteQuorum, timeout)

    case _: UpdateSuccess | _: ReplicationUpdateFailure ⇒
      // ReplicationUpdateFailure, will eventually be replicated
      becomeReady()

    case _ ⇒ stash()
  }

  def update(data: LWWMap, item: LineItem): LWWMap =
    data.get(item.productId) match {
      case Some(LineItem(_, _, existingQuantity)) ⇒
        data :+ (item.productId -> item.copy(quantity = existingQuantity + item.quantity))
      case None ⇒ data :+ (item.productId -> item)
      case _    ⇒ throw new IllegalStateException
    }

  override def unhandled(msg: Any): Unit = msg match {
    case e: UpdateFailure ⇒ throw new IllegalStateException("Unexpected failure: " + e)
    case _                ⇒ super.unhandled(msg)
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
    "join cluster" in {
      join(node1, node1)
      join(node2, node1)
      join(node3, node1)

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

    "read own updates" in within(5.seconds) {
      runOn(node2) {
        shoppingCart ! ShoppingCart.AddItem(LineItem("1", "Apples", quantity = 1))
        shoppingCart ! ShoppingCart.RemoveItem("3")
        shoppingCart ! ShoppingCart.AddItem(LineItem("3", "Bananas", quantity = 5))
        shoppingCart ! ShoppingCart.GetCart
        val cart = expectMsgType[Cart]
        cart.items should be(Set(LineItem("1", "Apples", quantity = 8), LineItem("3", "Bananas", quantity = 5)))
      }

      enterBarrier("after-4")
    }
  }

}

