/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import akka.actor._
import akka.event.EventHandler
import akka.dispatch.{ Future, Promise }
import akka.util.duration._
import akka.AkkaApplication

/**
 * Test whether TestActorRef behaves as an ActorRef should, besides its own spec.
 *
 * @author Roland Kuhn
 */

object TestActorRefSpec {

  var counter = 4
  val thread = Thread.currentThread
  var otherthread: Thread = null

  trait TActor extends Actor {
    def receive = new Receive {
      val recv = receiveT
      def isDefinedAt(o: Any) = recv.isDefinedAt(o)
      def apply(o: Any) {
        if (Thread.currentThread ne thread)
          otherthread = Thread.currentThread
        recv(o)
      }
    }
    def receiveT: Receive
  }

  class ReplyActor extends TActor {
    var replyTo: ActorRef = null

    def receiveT = {
      case "complexRequest" ⇒ {
        replyTo = sender
        val worker = TestActorRef(Props[WorkerActor])
        worker ! "work"
      }
      case "complexRequest2" ⇒
        val worker = TestActorRef(Props[WorkerActor])
        worker ! sender
      case "workDone"      ⇒ replyTo ! "complexReply"
      case "simpleRequest" ⇒ sender ! "simpleReply"
    }
  }

  class WorkerActor() extends TActor {
    def receiveT = {
      case "work"                ⇒ sender ! "workDone"; self.stop()
      case replyTo: Promise[Any] ⇒ replyTo.completeWithResult("complexReply")
      case replyTo: ActorRef     ⇒ replyTo ! "complexReply"
    }
  }

  class SenderActor(replyActor: ActorRef) extends TActor {

    def receiveT = {
      case "complex"  ⇒ replyActor ! "complexRequest"
      case "complex2" ⇒ replyActor ! "complexRequest2"
      case "simple"   ⇒ replyActor ! "simpleRequest"
      case "complexReply" ⇒ {
        counter -= 1
      }
      case "simpleReply" ⇒ {
        counter -= 1
      }
    }
  }

  class Logger extends Actor {
    import EventHandler._
    var count = 0
    var msg: String = _
    def receive = {
      case Warning(_, m: String) ⇒ count += 1; msg = m
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TestActorRefSpec extends AkkaSpec with BeforeAndAfterEach {

  import TestActorRefSpec._

  override def beforeEach {
    otherthread = null
  }

  private def assertThread {
    otherthread must (be(null) or equal(thread))
  }

  "A TestActorRef must be an ActorRef, hence it" must {

    "support nested Actor creation" when {

      "used with TestActorRef" in {
        val a = TestActorRef(Props(new Actor {
          val nested = TestActorRef(Props(self ⇒ { case _ ⇒ }))
          def receive = { case _ ⇒ sender ! nested }
        }))
        a must not be (null)
        val nested = (a ? "any").as[ActorRef].get
        nested must not be (null)
        a must not be theSameInstanceAs(nested)
      }

      "used with ActorRef" in {
        val a = TestActorRef(Props(new Actor {
          val nested = context.actorOf(Props(self ⇒ { case _ ⇒ }))
          def receive = { case _ ⇒ sender ! nested }
        }))
        a must not be (null)
        val nested = (a ? "any").as[ActorRef].get
        nested must not be (null)
        a must not be theSameInstanceAs(nested)
      }

    }

    "support reply via sender" in {
      val serverRef = TestActorRef(Props[ReplyActor])
      val clientRef = TestActorRef(Props(new SenderActor(serverRef)))

      counter = 4

      clientRef ! "complex"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      counter must be(0)

      counter = 4

      clientRef ! "complex2"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      counter must be(0)

      assertThread
    }

    "stop when sent a poison pill" in {
      filterEvents(EventFilter[ActorKilledException]) {
        val a = TestActorRef(Props[WorkerActor])
        testActor startsMonitoring a
        a.!(PoisonPill)(testActor)
        expectMsgPF(5 seconds) {
          case Terminated(`a`) ⇒ true
        }
        a must be('shutdown)
        assertThread
      }
    }

    "restart when Kill:ed" in {
      filterEvents(EventFilter[ActorKilledException]) {
        counter = 2

        val boss = TestActorRef(Props(new TActor {

          val ref = new TestActorRef(app, Props(new TActor {
            def receiveT = { case _ ⇒ }
            override def preRestart(reason: Throwable, msg: Option[Any]) { counter -= 1 }
            override def postRestart(reason: Throwable) { counter -= 1 }
          }), self, "child")

          def receiveT = { case "sendKill" ⇒ ref ! Kill }
        }).withFaultHandler(OneForOneStrategy(List(classOf[ActorKilledException]), 5, 1000)))

        boss ! "sendKill"

        counter must be(0)
        assertThread
      }
    }

    "support futures" in {
      val a = TestActorRef[WorkerActor]
      val f = a ? "work" mapTo manifest[String]
      f must be('completed)
      f.get must equal("workDone")
    }

  }

  "A TestActorRef" must {

    "allow access to internals" in {
      val ref = TestActorRef(new TActor {
        var s: String = _
        def receiveT = {
          case x: String ⇒ s = x
        }
      })
      ref ! "hallo"
      val actor = ref.underlyingActor
      actor.s must equal("hallo")
    }

    "set receiveTimeout to None" in {
      val a = TestActorRef[WorkerActor]
      a.underlyingActor.receiveTimeout must be(None)
    }

    "set CallingThreadDispatcher" in {
      val a = TestActorRef[WorkerActor]
      a.underlying.dispatcher.getClass must be(classOf[CallingThreadDispatcher])
    }

    "proxy apply for the underlying actor" in {
      val ref = TestActorRef[WorkerActor]
      ref("work")
      ref.isShutdown must be(true)
    }

  }
}
