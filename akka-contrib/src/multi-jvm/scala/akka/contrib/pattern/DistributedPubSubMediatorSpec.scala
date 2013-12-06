/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.actor.ActorLogging
import akka.contrib.pattern.DistributedPubSubMediator.Internal.Status
import akka.contrib.pattern.DistributedPubSubMediator.Internal.Delta

object DistributedPubSubMediatorSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.contrib.cluster.pub-sub.max-delta-elements = 500
    #akka.remote.log-frame-size-exceeding = 1024b
    """))

  object TestChatUser {
    case class Whisper(path: String, msg: Any)
    case class Talk(path: String, msg: Any)
    case class TalkToOthers(path: String, msg: Any)
    case class Shout(topic: String, msg: Any)
  }

  class TestChatUser(mediator: ActorRef, testActor: ActorRef) extends Actor {
    import TestChatUser._
    import DistributedPubSubMediator._

    def receive = {
      case Whisper(path, msg)      ⇒ mediator ! Send(path, msg, localAffinity = true)
      case Talk(path, msg)         ⇒ mediator ! SendToAll(path, msg)
      case TalkToOthers(path, msg) ⇒ mediator ! SendToAll(path, msg, allButSelf = true)
      case Shout(topic, msg)       ⇒ mediator ! Publish(topic, msg)
      case msg                     ⇒ testActor ! msg
    }
  }

  //#publisher
  class Publisher extends Actor {
    import DistributedPubSubMediator.Publish
    // activate the extension
    val mediator = DistributedPubSubExtension(context.system).mediator

    def receive = {
      case in: String ⇒
        val out = in.toUpperCase
        mediator ! Publish("content", out)
    }
  }
  //#publisher

  //#subscriber
  class Subscriber extends Actor with ActorLogging {
    import DistributedPubSubMediator.{ Subscribe, SubscribeAck }
    val mediator = DistributedPubSubExtension(context.system).mediator
    // subscribe to the topic named "content"
    mediator ! Subscribe("content", self)

    def receive = {
      case SubscribeAck(Subscribe("content", `self`)) ⇒
        context become ready
    }

    def ready: Actor.Receive = {
      case s: String ⇒
        log.info("Got {}", s)
    }
  }
  //#subscriber

}

class DistributedPubSubMediatorMultiJvmNode1 extends DistributedPubSubMediatorSpec
class DistributedPubSubMediatorMultiJvmNode2 extends DistributedPubSubMediatorSpec
class DistributedPubSubMediatorMultiJvmNode3 extends DistributedPubSubMediatorSpec

class DistributedPubSubMediatorSpec extends MultiNodeSpec(DistributedPubSubMediatorSpec) with STMultiNodeSpec with ImplicitSender {
  import DistributedPubSubMediatorSpec._
  import DistributedPubSubMediatorSpec.TestChatUser._
  import DistributedPubSubMediator._

  override def initialParticipants = roles.size

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      createMediator()
    }
    enterBarrier(from.name + "-joined")
  }

  def createMediator(): ActorRef = DistributedPubSubExtension(system).mediator
  def mediator: ActorRef = DistributedPubSubExtension(system).mediator

  var chatUsers: Map[String, ActorRef] = Map.empty

  def createChatUser(name: String): ActorRef = {
    var a = system.actorOf(Props(classOf[TestChatUser], mediator, testActor), name)
    chatUsers += (name -> a)
    a
  }

  def chatUser(name: String): ActorRef = chatUsers(name)

  def awaitCount(expected: Int): Unit = {
    awaitAssert {
      mediator ! Count
      expectMsgType[Int] must be(expected)
    }
  }

  "A DistributedPubSubMediator" must {

    "startup 2 node cluster" in within(15 seconds) {
      join(first, first)
      join(second, first)
      enterBarrier("after-1")
    }

    "keep track of added users" in within(15 seconds) {
      runOn(first) {
        val u1 = createChatUser("u1")
        mediator ! Put(u1)

        val u2 = createChatUser("u2")
        mediator ! Put(u2)

        awaitCount(2)

        // send to actor at same node
        u1 ! Whisper("/user/u2", "hello")
        expectMsg("hello")
        lastSender must be(u2)
      }

      runOn(second) {
        val u3 = createChatUser("u3")
        mediator ! Put(u3)
      }

      runOn(first, second) {
        awaitCount(3)
      }
      enterBarrier("3-registered")

      runOn(second) {
        val u4 = createChatUser("u4")
        mediator ! Put(u4)
      }

      runOn(first, second) {
        awaitCount(4)
      }
      enterBarrier("4-registered")

      runOn(first) {
        // send to actor on another node
        chatUser("u1") ! Whisper("/user/u4", "hi there")
      }

      runOn(second) {
        expectMsg("hi there")
        lastSender.path.name must be("u4")
      }

      enterBarrier("after-2")
    }

    "replicate users to new node" in within(20 seconds) {
      join(third, first)

      runOn(third) {
        val u5 = createChatUser("u5")
        mediator ! Put(u5)
      }

      awaitCount(5)
      enterBarrier("5-registered")

      runOn(third) {
        chatUser("u5") ! Whisper("/user/u4", "go")
      }

      runOn(second) {
        expectMsg("go")
        lastSender.path.name must be("u4")
      }

      enterBarrier("after-3")
    }

    "keep track of removed users" in within(15 seconds) {
      runOn(first) {
        val u6 = createChatUser("u6")
        mediator ! Put(u6)
      }
      awaitCount(6)
      enterBarrier("6-registered")

      runOn(first) {
        mediator ! Remove("/user/u6")
      }
      awaitCount(5)

      enterBarrier("after-4")
    }

    "remove terminated users" in within(5 seconds) {
      runOn(second) {
        chatUser("u3") ! PoisonPill
      }

      awaitCount(4)
      enterBarrier("after-5")
    }

    "publish" in within(15 seconds) {
      runOn(first, second) {
        val u7 = createChatUser("u7")
        mediator ! Put(u7)
      }
      awaitCount(6)
      enterBarrier("7-registered")

      runOn(third) {
        chatUser("u5") ! Talk("/user/u7", "hi")
      }

      runOn(first, second) {
        expectMsg("hi")
        lastSender.path.name must be("u7")
      }
      runOn(third) {
        expectNoMsg(2.seconds)
      }

      enterBarrier("after-6")
    }

    "publish to topic" in within(15 seconds) {
      runOn(first) {
        val s8 = Subscribe("topic1", createChatUser("u8"))
        mediator ! s8
        expectMsg(SubscribeAck(s8))
        val s9 = Subscribe("topic1", createChatUser("u9"))
        mediator ! s9
        expectMsg(SubscribeAck(s9))
      }
      runOn(second) {
        val s10 = Subscribe("topic1", createChatUser("u10"))
        mediator ! s10
        expectMsg(SubscribeAck(s10))
      }
      // one topic on two nodes
      awaitCount(8)
      enterBarrier("topic1-registered")

      runOn(third) {
        chatUser("u5") ! Shout("topic1", "hello all")
      }

      runOn(first) {
        val names = receiveWhile(messages = 2) {
          case "hello all" ⇒ lastSender.path.name
        }
        names.toSet must be(Set("u8", "u9"))
      }
      runOn(second) {
        expectMsg("hello all")
        lastSender.path.name must be("u10")
      }
      runOn(third) {
        expectNoMsg(2.seconds)
      }

      enterBarrier("after-7")
    }

    "demonstrate usage" in within(15 seconds) {
      def later(): Unit = {
        awaitCount(10)
      }

      //#start-subscribers
      runOn(first) {
        system.actorOf(Props[Subscriber], "subscriber1")
      }
      runOn(second) {
        system.actorOf(Props[Subscriber], "subscriber2")
        system.actorOf(Props[Subscriber], "subscriber3")
      }
      //#start-subscribers

      //#publish-message
      runOn(third) {
        val publisher = system.actorOf(Props[Publisher], "publisher")
        later()
        // after a while the subscriptions are replicated
        publisher ! "hello"
      }
      //#publish-message

      enterBarrier("after-8")
    }

    "send-all to all other nodes" in within(15 seconds) {
      runOn(first, second, third) { // create the user on all nodes
        val u11 = createChatUser("u11")
        mediator ! Put(u11)
      }
      awaitCount(13)
      enterBarrier("11-registered")

      runOn(third) {
        chatUser("u5") ! TalkToOthers("/user/u11", "hi") // sendToAll to all other nodes
      }

      runOn(first, second) {
        expectMsg("hi")
        lastSender.path.name must be("u11")
      }
      runOn(third) {
        expectNoMsg(2.seconds) // sender node should not receive a message
      }

      enterBarrier("after-11")
    }

    "transfer delta correctly" in {
      val firstAddress = node(first).address
      val secondAddress = node(second).address
      val thirdAddress = node(third).address

      runOn(first) {
        mediator ! Status(versions = Map.empty)
        val deltaBuckets = expectMsgType[Delta].buckets
        deltaBuckets.size must be(3)
        deltaBuckets.find(_.owner == firstAddress).get.content.size must be(7)
        deltaBuckets.find(_.owner == secondAddress).get.content.size must be(6)
        deltaBuckets.find(_.owner == thirdAddress).get.content.size must be(2)
      }
      enterBarrier("verified-initial-delta")

      // this test is configured with max-delta-elements = 500
      val many = 1010
      runOn(first) {
        for (i ← 0 until many)
          mediator ! Put(createChatUser("u" + (1000 + i)))

        mediator ! Status(versions = Map.empty)
        val deltaBuckets1 = expectMsgType[Delta].buckets
        deltaBuckets1.map(_.content.size).sum must be(500)

        mediator ! Status(versions = deltaBuckets1.map(b ⇒ b.owner -> b.version).toMap)
        val deltaBuckets2 = expectMsgType[Delta].buckets
        deltaBuckets1.map(_.content.size).sum must be(500)

        mediator ! Status(versions = deltaBuckets2.map(b ⇒ b.owner -> b.version).toMap)
        val deltaBuckets3 = expectMsgType[Delta].buckets

        deltaBuckets3.map(_.content.size).sum must be(7 + 6 + 2 + many - 500 - 500)
      }

      enterBarrier("verified-delta-with-many")
      within(10.seconds) {
        awaitCount(13 + many)
      }

      enterBarrier("after-12")
    }
  }
}
