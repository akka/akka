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
import java.net.InetSocketAddress
import java.net.InetAddress
import akka.testkit.TimingTest
import akka.util.{ Timeout, Duration }

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

  val A = RoleName("a")
  val B = RoleName("b")
  val C = RoleName("c")

  override def afterEach {
    system.eventStream.setLogLevel(Logging.WarningLevel)
  }

  "A BarrierCoordinator" must {

    "register clients and remove them" taggedAs TimingTest in {
      val b = getBarrier()
      b ! NodeInfo(A, AddressFromURIString("akka://sys"), system.deadLetters)
      b ! RemoveClient(B)
      b ! RemoveClient(A)
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        b ! RemoveClient(A)
      }
      expectMsg(Failed(b, BarrierEmpty(Data(Set(), "", Nil), "cannot remove RoleName(a): no client to remove")))
    }

    "register clients and disconnect them" taggedAs TimingTest in {
      val b = getBarrier()
      b ! NodeInfo(A, AddressFromURIString("akka://sys"), system.deadLetters)
      b ! ClientDisconnected(B)
      EventFilter[ClientLost](occurrences = 1) intercept {
        b ! ClientDisconnected(A)
      }
      expectMsg(Failed(b, ClientLost(Data(Set(), "", Nil), A)))
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        b ! ClientDisconnected(A)
      }
      expectMsg(Failed(b, BarrierEmpty(Data(Set(), "", Nil), "cannot disconnect RoleName(a): no client to disconnect")))
    }

    "fail entering barrier when nobody registered" taggedAs TimingTest in {
      val b = getBarrier()
      b ! EnterBarrier("bar1")
      expectMsg(ToClient(BarrierResult("bar1", false)))
    }

    "enter barrier" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      a.send(barrier, EnterBarrier("bar2"))
      noMsg(a, b)
      within(2 second) {
        b.send(barrier, EnterBarrier("bar2"))
        a.expectMsg(ToClient(BarrierResult("bar2", true)))
        b.expectMsg(ToClient(BarrierResult("bar2", true)))
      }
    }

    "enter barrier with joining node" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b, c = TestProbe()
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      a.send(barrier, EnterBarrier("bar3"))
      barrier ! NodeInfo(C, AddressFromURIString("akka://sys"), c.ref)
      b.send(barrier, EnterBarrier("bar3"))
      noMsg(a, b, c)
      within(2 second) {
        c.send(barrier, EnterBarrier("bar3"))
        a.expectMsg(ToClient(BarrierResult("bar3", true)))
        b.expectMsg(ToClient(BarrierResult("bar3", true)))
        c.expectMsg(ToClient(BarrierResult("bar3", true)))
      }
    }

    "enter barrier with leaving node" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b, c = TestProbe()
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! NodeInfo(C, AddressFromURIString("akka://sys"), c.ref)
      a.send(barrier, EnterBarrier("bar4"))
      b.send(barrier, EnterBarrier("bar4"))
      barrier ! RemoveClient(A)
      barrier ! ClientDisconnected(A)
      noMsg(a, b, c)
      b.within(2 second) {
        barrier ! RemoveClient(C)
        b.expectMsg(ToClient(BarrierResult("bar4", true)))
      }
      barrier ! ClientDisconnected(C)
      expectNoMsg(1 second)
    }

    "leave barrier when last “arrived” is removed" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      a.send(barrier, EnterBarrier("bar5"))
      barrier ! RemoveClient(A)
      b.send(barrier, EnterBarrier("foo"))
      b.expectMsg(ToClient(BarrierResult("foo", true)))
    }

    "fail barrier with disconnecing node" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! nodeA
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      a.send(barrier, EnterBarrier("bar6"))
      EventFilter[ClientLost](occurrences = 1) intercept {
        barrier ! ClientDisconnected(B)
      }
      expectMsg(Failed(barrier, ClientLost(Data(Set(nodeA), "bar6", a.ref :: Nil), B)))
    }

    "fail barrier with disconnecing node who already arrived" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b, c = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      val nodeC = NodeInfo(C, AddressFromURIString("akka://sys"), c.ref)
      barrier ! nodeA
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeC
      a.send(barrier, EnterBarrier("bar7"))
      b.send(barrier, EnterBarrier("bar7"))
      EventFilter[ClientLost](occurrences = 1) intercept {
        barrier ! ClientDisconnected(B)
      }
      expectMsg(Failed(barrier, ClientLost(Data(Set(nodeA, nodeC), "bar7", a.ref :: Nil), B)))
    }

    "fail when entering wrong barrier" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! nodeA
      val nodeB = NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeB
      a.send(barrier, EnterBarrier("bar8"))
      EventFilter[WrongBarrier](occurrences = 1) intercept {
        b.send(barrier, EnterBarrier("foo"))
      }
      expectMsg(Failed(barrier, WrongBarrier("foo", b.ref, Data(Set(nodeA, nodeB), "bar8", a.ref :: Nil))))
    }

    "fail barrier after first failure" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a = TestProbe()
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        barrier ! RemoveClient(A)
      }
      expectMsg(Failed(barrier, BarrierEmpty(Data(Set(), "", Nil), "cannot remove RoleName(a): no client to remove")))
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      a.send(barrier, EnterBarrier("bar9"))
      a.expectMsg(ToClient(BarrierResult("bar9", false)))
    }

    "fail after barrier timeout" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeA
      barrier ! nodeB
      a.send(barrier, EnterBarrier("bar10"))
      EventFilter[BarrierTimeout](occurrences = 1) intercept {
        expectMsg(7 seconds, Failed(barrier, BarrierTimeout(Data(Set(nodeA, nodeB), "bar10", a.ref :: Nil))))
      }
    }

    "fail if a node registers twice" taggedAs TimingTest in {
      val barrier = getBarrier()
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo(A, AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeA
      EventFilter[DuplicateNode](occurrences = 1) intercept {
        barrier ! nodeB
      }
      expectMsg(Failed(barrier, DuplicateNode(Data(Set(nodeA), "", Nil), nodeB)))
    }

    "finally have no failure messages left" taggedAs TimingTest in {
      expectNoMsg(1 second)
    }

  }

  "A Controller with BarrierCoordinator" must {

    "register clients and remove them" taggedAs TimingTest in {
      val b = getController(1)
      b ! NodeInfo(A, AddressFromURIString("akka://sys"), testActor)
      expectMsg(ToClient(Done))
      b ! Remove(B)
      b ! Remove(A)
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        b ! Remove(A)
      }
    }

    "register clients and disconnect them" taggedAs TimingTest in {
      val b = getController(1)
      b ! NodeInfo(A, AddressFromURIString("akka://sys"), testActor)
      expectMsg(ToClient(Done))
      b ! ClientDisconnected(B)
      EventFilter[ClientLost](occurrences = 1) intercept {
        b ! ClientDisconnected(A)
      }
      EventFilter[BarrierEmpty](occurrences = 1) intercept {
        b ! ClientDisconnected(A)
      }
    }

    "fail entering barrier when nobody registered" taggedAs TimingTest in {
      val b = getController(0)
      b ! EnterBarrier("b")
      expectMsg(ToClient(BarrierResult("b", false)))
    }

    "enter barrier" taggedAs TimingTest in {
      val barrier = getController(2)
      val a, b = TestProbe()
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar11"))
      noMsg(a, b)
      within(2 second) {
        b.send(barrier, EnterBarrier("bar11"))
        a.expectMsg(ToClient(BarrierResult("bar11", true)))
        b.expectMsg(ToClient(BarrierResult("bar11", true)))
      }
    }

    "enter barrier with joining node" taggedAs TimingTest in {
      val barrier = getController(2)
      val a, b, c = TestProbe()
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar12"))
      barrier ! NodeInfo(C, AddressFromURIString("akka://sys"), c.ref)
      c.expectMsg(ToClient(Done))
      b.send(barrier, EnterBarrier("bar12"))
      noMsg(a, b, c)
      within(2 second) {
        c.send(barrier, EnterBarrier("bar12"))
        a.expectMsg(ToClient(BarrierResult("bar12", true)))
        b.expectMsg(ToClient(BarrierResult("bar12", true)))
        c.expectMsg(ToClient(BarrierResult("bar12", true)))
      }
    }

    "enter barrier with leaving node" taggedAs TimingTest in {
      val barrier = getController(3)
      val a, b, c = TestProbe()
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! NodeInfo(C, AddressFromURIString("akka://sys"), c.ref)
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      c.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar13"))
      b.send(barrier, EnterBarrier("bar13"))
      barrier ! Remove(A)
      barrier ! ClientDisconnected(A)
      noMsg(a, b, c)
      b.within(2 second) {
        barrier ! Remove(C)
        b.expectMsg(ToClient(BarrierResult("bar13", true)))
      }
      barrier ! ClientDisconnected(C)
      expectNoMsg(1 second)
    }

    "leave barrier when last “arrived” is removed" taggedAs TimingTest in {
      val barrier = getController(2)
      val a, b = TestProbe()
      barrier ! NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar14"))
      barrier ! Remove(A)
      b.send(barrier, EnterBarrier("foo"))
      b.expectMsg(ToClient(BarrierResult("foo", true)))
    }

    "fail barrier with disconnecing node" taggedAs TimingTest in {
      val barrier = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! nodeA
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar15"))
      barrier ! ClientDisconnected(RoleName("unknown"))
      noMsg(a)
      EventFilter[ClientLost](occurrences = 1) intercept {
        barrier ! ClientDisconnected(B)
      }
      a.expectMsg(ToClient(BarrierResult("bar15", false)))
    }

    "fail barrier with disconnecing node who already arrived" taggedAs TimingTest in {
      val barrier = getController(3)
      val a, b, c = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      val nodeC = NodeInfo(C, AddressFromURIString("akka://sys"), c.ref)
      barrier ! nodeA
      barrier ! NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeC
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      c.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar16"))
      b.send(barrier, EnterBarrier("bar16"))
      EventFilter[ClientLost](occurrences = 1) intercept {
        barrier ! ClientDisconnected(B)
      }
      a.expectMsg(ToClient(BarrierResult("bar16", false)))
    }

    "fail when entering wrong barrier" taggedAs TimingTest in {
      val barrier = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      barrier ! nodeA
      val nodeB = NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeB
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar17"))
      EventFilter[WrongBarrier](occurrences = 1) intercept {
        b.send(barrier, EnterBarrier("foo"))
      }
      a.expectMsg(ToClient(BarrierResult("bar17", false)))
      b.expectMsg(ToClient(BarrierResult("foo", false)))
    }

    "fail after barrier timeout" taggedAs TimingTest in {
      val barrier = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeA
      barrier ! nodeB
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar18", Option(Timeout.durationToTimeout(2 seconds))))
      EventFilter[BarrierTimeout](occurrences = 1) intercept {
        Thread.sleep(4000)
      }
      b.send(barrier, EnterBarrier("bar18"))
      a.expectMsg(ToClient(BarrierResult("bar18", false)))
      b.expectMsg(ToClient(BarrierResult("bar18", false)))
    }

    "fail if a node registers twice" taggedAs TimingTest in {
      val controller = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo(A, AddressFromURIString("akka://sys"), b.ref)
      controller ! nodeA
      EventFilter[DuplicateNode](occurrences = 1) intercept {
        controller ! nodeB
      }
      a.expectMsg(ToClient(BarrierResult("initial startup", false)))
      b.expectMsg(ToClient(BarrierResult("initial startup", false)))
    }

    "fail subsequent barriers if a node registers twice" taggedAs TimingTest in {
      val controller = getController(1)
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo(A, AddressFromURIString("akka://sys"), b.ref)
      controller ! nodeA
      a.expectMsg(ToClient(Done))
      EventFilter[DuplicateNode](occurrences = 1) intercept {
        controller ! nodeB
        b.expectMsg(ToClient(BarrierResult("initial startup", false)))
      }
      a.send(controller, EnterBarrier("bar19"))
      a.expectMsg(ToClient(BarrierResult("bar19", false)))
    }

    "fail subsequent barriers after foreced failure" taggedAs TimingTest in {
      val barrier = getController(2)
      val a, b = TestProbe()
      val nodeA = NodeInfo(A, AddressFromURIString("akka://sys"), a.ref)
      val nodeB = NodeInfo(B, AddressFromURIString("akka://sys"), b.ref)
      barrier ! nodeA
      barrier ! nodeB
      a.expectMsg(ToClient(Done))
      b.expectMsg(ToClient(Done))
      a.send(barrier, EnterBarrier("bar20", Option(Timeout.durationToTimeout(2 seconds))))
      b.send(barrier, FailBarrier("bar20"))
      a.expectMsg(ToClient(BarrierResult("bar20", false)))
      b.expectNoMsg(1 second)
      a.send(barrier, EnterBarrier("bar21"))
      b.send(barrier, EnterBarrier("bar21"))
      a.expectMsg(ToClient(BarrierResult("bar21", false)))
      b.expectMsg(ToClient(BarrierResult("bar21", false)))
    }

    "finally have no failure messages left" taggedAs TimingTest in {
      expectNoMsg(1 second)
    }

  }

  private def getController(participants: Int): ActorRef = {
    system.actorOf(Props(new Actor {
      val controller = context.actorOf(Props(new Controller(participants, new InetSocketAddress(InetAddress.getLocalHost, 0))))
      controller ! GetSockAddr
      override def supervisorStrategy = OneForOneStrategy() {
        case x ⇒ testActor ! Failed(controller, x); SupervisorStrategy.Restart
      }
      def receive = {
        case x: InetSocketAddress ⇒ testActor ! controller
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