/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.actor.AddressFromURIString
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import akka.actor.Actor
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.testkit.EventFilter
import akka.testkit.TestProbe
import akka.util.duration._
import akka.event.Logging
import org.scalatest.BeforeAndAfterEach

object BarrierSpec {
  case class Failed(ref: ActorRef, thr: Throwable)
  val config = """
    akka.testconductor.barrier-timeout = 5s
    akka.actor.provider = akka.remote.RemoteActorRefProvider
    akka.remote.netty.port = 0
    akka.actor.debug.fsm = on
    akka.actor.debug.lifecycle = on
    """
}

class BarrierSpec extends AkkaSpec(BarrierSpec.config) with ImplicitSender with BeforeAndAfterEach {

  import BarrierSpec._
  import Controller._
  import BarrierCoordinator._

  override def afterEach {
    system.eventStream.setLogLevel(Logging.WarningLevel)
  }

  "A BarrierCoordinator" must {

    "register clients and remove them" in {
      val b = getBarrier()
      b ! NodeInfo("a", AddressFromURIString("akka://sys"), system.deadLetters)
      b ! RemoveClient("b")
      b ! RemoveClient("a")
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        b ! RemoveClient("a")
      }
      expectMsg(Failed(b, BarrierEmpty(Data(Set(), "", Nil), "no client to remove")))
    }

    "register clients and disconnect them" in {
      val b = getBarrier()
      b ! NodeInfo("a", AddressFromURIString("akka://sys"), system.deadLetters)
      b ! ClientDisconnected("b")
      EventFilter[ClientLost](occurrences = 1) intercept {
        b ! ClientDisconnected("a")
      }
      expectMsg(Failed(b, ClientLost(Data(Set(), "", Nil), "a")))
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        b ! ClientDisconnected("a")
      }
      expectMsg(Failed(b, BarrierEmpty(Data(Set(), "", Nil), "no client to disconnect")))
    }

    "fail entering barrier when nobody registered" in {
      val b = getBarrier()
      b ! EnterBarrier("b")
      expectMsg(Send(BarrierFailed("b")))
    }

    "enter barrier" in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      a.send(barrier, EnterBarrier("bar"))
      noMsg(a, b)
      within(1 second) {
        b.send(barrier, EnterBarrier("bar"))
        a.expectMsg(Send(EnterBarrier("bar")))
        b.expectMsg(Send(EnterBarrier("bar")))
      }
    }

    "enter barrier with joining node" in {
      val barrier = getBarrier()
      val a, b, c = TestProbe()
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      a.send(barrier, EnterBarrier("bar"))
      barrier ! NodeInfo("c", AddressFromURIString("akka://sys"), c.ref)
      b.send(barrier, EnterBarrier("bar"))
      noMsg(a, b, c)
      within(1 second) {
        c.send(barrier, EnterBarrier("bar"))
        a.expectMsg(Send(EnterBarrier("bar")))
        b.expectMsg(Send(EnterBarrier("bar")))
        c.expectMsg(Send(EnterBarrier("bar")))
      }
    }

    "enter barrier with leaving node" in {
      val barrier = getBarrier()
      val a, b, c = TestProbe()
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      barrier ! NodeInfo("c", AddressFromURIString("akka://sys"), c.ref)
      a.send(barrier, EnterBarrier("bar"))
      b.send(barrier, EnterBarrier("bar"))
      barrier ! RemoveClient("a")
      barrier ! ClientDisconnected("a")
      noMsg(a, b, c)
      b.within(1 second) {
        barrier ! RemoveClient("c")
        b.expectMsg(Send(EnterBarrier("bar")))
      }
      barrier ! ClientDisconnected("c")
      expectNoMsg(1 second)
    }

    "leave barrier when last “arrived” is removed" in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      a.send(barrier, EnterBarrier("bar"))
      barrier ! RemoveClient("a")
      b.send(barrier, EnterBarrier("foo"))
      b.expectMsg(Send(EnterBarrier("foo")))
    }

    "fail barrier with disconnecing node" in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! nodeA
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      a.send(barrier, EnterBarrier("bar"))
      EventFilter[ClientLost](occurrences = 1) intercept {
        barrier ! ClientDisconnected("b")
      }
      expectMsg(Failed(barrier, ClientLost(Data(Set(nodeA), "bar", a.ref :: Nil), "b")))
    }

    "fail barrier with disconnecing node who already arrived" in {
      val barrier = getBarrier()
      val a, b, c = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      val nodeC = NodeInfo("c", AddressFromURIString("akka://sys"), c.ref)
      barrier ! nodeA
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeC
      a.send(barrier, EnterBarrier("bar"))
      b.send(barrier, EnterBarrier("bar"))
      EventFilter[ClientLost](occurrences = 1) intercept {
        barrier ! ClientDisconnected("b")
      }
      expectMsg(Failed(barrier, ClientLost(Data(Set(nodeA, nodeC), "bar", a.ref :: Nil), "b")))
    }

    "fail when entering wrong barrier" in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! nodeA
      val nodeB = NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeB
      a.send(barrier, EnterBarrier("bar"))
      EventFilter[WrongBarrier](occurrences = 1) intercept {
        b.send(barrier, EnterBarrier("foo"))
      }
      expectMsg(Failed(barrier, WrongBarrier("foo", b.ref, Data(Set(nodeA, nodeB), "bar", a.ref :: Nil))))
    }

    "fail barrier after first failure" in {
      val barrier = getBarrier()
      val a = TestProbe()
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        barrier ! RemoveClient("a")
      }
      expectMsg(Failed(barrier, BarrierEmpty(Data(Set(), "", Nil), "no client to remove")))
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      a.send(barrier, EnterBarrier("right"))
      a.expectMsg(Send(BarrierFailed("right")))
    }

    "fail after barrier timeout" in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeA
      barrier ! nodeB
      a.send(barrier, EnterBarrier("right"))
      EventFilter[BarrierTimeout](occurrences = 1) intercept {
        expectMsg(7 seconds, Failed(barrier, BarrierTimeout(Data(Set(nodeA, nodeB), "right", a.ref :: Nil))))
      }
    }

    "fail if a node registers twice" in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo("a", AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeA
      EventFilter[DuplicateNode](occurrences = 1) intercept {
        barrier ! nodeB
      }
      expectMsg(Failed(barrier, DuplicateNode(Data(Set(nodeA), "", Nil), nodeB)))
    }

    "finally have no failure messages left" in {
      expectNoMsg(1 second)
    }

  }

  "A Controller with BarrierCoordinator" must {

    "register clients and remove them" in {
      val b = getController(1)
      b ! NodeInfo("a", AddressFromURIString("akka://sys"), testActor)
      expectMsg(Send(Done))
      b ! Remove("b")
      b ! Remove("a")
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        b ! Remove("a")
      }
    }

    "register clients and disconnect them" in {
      val b = getController(1)
      b ! NodeInfo("a", AddressFromURIString("akka://sys"), testActor)
      expectMsg(Send(Done))
      b ! ClientDisconnected("b")
      EventFilter[ClientLost](occurrences = 1) intercept {
        b ! ClientDisconnected("a")
      }
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        b ! ClientDisconnected("a")
      }
    }

    "fail entering barrier when nobody registered" in {
      val b = getController(0)
      b ! EnterBarrier("b")
      expectMsg(Send(BarrierFailed("b")))
    }

    "enter barrier" in {
      val barrier = getController(2)
      val a, b = TestProbe()
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      a.expectMsg(Send(Done))
      b.expectMsg(Send(Done))
      a.send(barrier, EnterBarrier("bar"))
      noMsg(a, b)
      within(1 second) {
        b.send(barrier, EnterBarrier("bar"))
        a.expectMsg(Send(EnterBarrier("bar")))
        b.expectMsg(Send(EnterBarrier("bar")))
      }
    }

    "enter barrier with joining node" in {
      val barrier = getController(2)
      val a, b, c = TestProbe()
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      a.expectMsg(Send(Done))
      b.expectMsg(Send(Done))
      a.send(barrier, EnterBarrier("bar"))
      barrier ! NodeInfo("c", AddressFromURIString("akka://sys"), c.ref)
      c.expectMsg(Send(Done))
      b.send(barrier, EnterBarrier("bar"))
      noMsg(a, b, c)
      within(1 second) {
        c.send(barrier, EnterBarrier("bar"))
        a.expectMsg(Send(EnterBarrier("bar")))
        b.expectMsg(Send(EnterBarrier("bar")))
        c.expectMsg(Send(EnterBarrier("bar")))
      }
    }

    "enter barrier with leaving node" in {
      val barrier = getController(3)
      val a, b, c = TestProbe()
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      barrier ! NodeInfo("c", AddressFromURIString("akka://sys"), c.ref)
      a.expectMsg(Send(Done))
      b.expectMsg(Send(Done))
      c.expectMsg(Send(Done))
      a.send(barrier, EnterBarrier("bar"))
      b.send(barrier, EnterBarrier("bar"))
      barrier ! Remove("a")
      barrier ! ClientDisconnected("a")
      noMsg(a, b, c)
      b.within(1 second) {
        barrier ! Remove("c")
        b.expectMsg(Send(EnterBarrier("bar")))
      }
      barrier ! ClientDisconnected("c")
      expectNoMsg(1 second)
    }

    "leave barrier when last “arrived” is removed" in {
      val barrier = getController(2)
      val a, b = TestProbe()
      barrier ! NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      a.expectMsg(Send(Done))
      b.expectMsg(Send(Done))
      a.send(barrier, EnterBarrier("bar"))
      barrier ! Remove("a")
      b.send(barrier, EnterBarrier("foo"))
      b.expectMsg(Send(EnterBarrier("foo")))
    }

    "fail barrier with disconnecing node" in {
      val barrier = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! nodeA
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      a.expectMsg(Send(Done))
      b.expectMsg(Send(Done))
      a.send(barrier, EnterBarrier("bar"))
      barrier ! ClientDisconnected("unknown")
      noMsg(a)
      EventFilter[ClientLost](occurrences = 1) intercept {
        barrier ! ClientDisconnected("b")
      }
      a.expectMsg(Send(BarrierFailed("bar")))
    }

    "fail barrier with disconnecing node who already arrived" in {
      val barrier = getController(3)
      val a, b, c = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      val nodeC = NodeInfo("c", AddressFromURIString("akka://sys"), c.ref)
      barrier ! nodeA
      barrier ! NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeC
      a.expectMsg(Send(Done))
      b.expectMsg(Send(Done))
      c.expectMsg(Send(Done))
      a.send(barrier, EnterBarrier("bar"))
      b.send(barrier, EnterBarrier("bar"))
      EventFilter[ClientLost](occurrences = 1) intercept {
        barrier ! ClientDisconnected("b")
      }
      a.expectMsg(Send(BarrierFailed("bar")))
    }

    "fail when entering wrong barrier" in {
      val barrier = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      barrier ! nodeA
      val nodeB = NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeB
      a.expectMsg(Send(Done))
      b.expectMsg(Send(Done))
      a.send(barrier, EnterBarrier("bar"))
      EventFilter[WrongBarrier](occurrences = 1) intercept {
        b.send(barrier, EnterBarrier("foo"))
      }
      a.expectMsg(Send(BarrierFailed("bar")))
      b.expectMsg(Send(BarrierFailed("foo")))
    }

    "not really fail after barrier timeout" in {
      val barrier = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo("b", AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeA
      barrier ! nodeB
      a.expectMsg(Send(Done))
      b.expectMsg(Send(Done))
      a.send(barrier, EnterBarrier("right"))
      EventFilter[BarrierTimeout](occurrences = 1) intercept {
        Thread.sleep(5000)
      }
      b.send(barrier, EnterBarrier("right"))
      a.expectMsg(Send(EnterBarrier("right")))
      b.expectMsg(Send(EnterBarrier("right")))
    }

    "fail if a node registers twice" in {
      val controller = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo("a", AddressFromURIString("akka://sys"), b.ref)
      controller ! nodeA
      EventFilter[DuplicateNode](occurrences = 1) intercept {
        controller ! nodeB
      }
      a.expectMsg(Send(BarrierFailed("initial startup")))
      b.expectMsg(Send(BarrierFailed("initial startup")))
    }

    "fail subsequent barriers if a node registers twice" in {
      val controller = getController(1)
      val a, b = TestProbe()
      val nodeA = NodeInfo("a", AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo("a", AddressFromURIString("akka://sys"), b.ref)
      controller ! nodeA
      a.expectMsg(Send(Done))
      EventFilter[DuplicateNode](occurrences = 1) intercept {
        controller ! nodeB
        b.expectMsg(Send(BarrierFailed("initial startup")))
      }
      a.send(controller, EnterBarrier("x"))
      a.expectMsg(Send(BarrierFailed("x")))
    }

    "finally have no failure messages left" in {
      expectNoMsg(1 second)
    }

  }

  private def getController(participants: Int): ActorRef = {
    system.actorOf(Props(new Actor {
      val controller = context.actorOf(Props(new Controller(participants)))
      controller ! GetPort
      override def supervisorStrategy = OneForOneStrategy() {
        case x ⇒ testActor ! Failed(controller, x); SupervisorStrategy.Restart
      }
      def receive = {
        case x: Int ⇒ testActor ! controller
      }
    }))
    expectMsgType[ActorRef]
  }

  /**
   * Produce a BarrierCoordinator which is supervised with a strategy which
   * forwards all failures to the testActor.
   */
  private def getBarrier(): ActorRef = {
    system.actorOf(Props(new Actor {
      val barrier = context.actorOf(Props[BarrierCoordinator])
      override def supervisorStrategy = OneForOneStrategy() {
        case x ⇒ testActor ! Failed(barrier, x); SupervisorStrategy.Restart
      }
      def receive = {
        case _ ⇒ sender ! barrier
      }
    })) ! ""
    expectMsgType[ActorRef]
  }

  private def noMsg(probes: TestProbe*) {
    expectNoMsg(1 second)
    probes foreach (_.msgAvailable must be(false))
  }

}