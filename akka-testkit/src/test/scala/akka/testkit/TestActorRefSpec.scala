/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.testkit

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import akka.actor._
import akka.config.Supervision.OneForOneStrategy
import akka.event.EventHandler
import akka.dispatch.Future

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
    var replyTo: Channel[Any] = null

    def receiveT = {
      case "complexRequest" ⇒ {
        replyTo = self.channel
        val worker = TestActorRef[WorkerActor].start()
        worker ! "work"
      }
      case "complexRequest2" ⇒
        val worker = TestActorRef[WorkerActor].start()
        worker ! self.channel
      case "workDone"      ⇒ replyTo ! "complexReply"
      case "simpleRequest" ⇒ self.reply("simpleReply")
    }
  }

  class WorkerActor() extends TActor {
    def receiveT = {
      case "work" ⇒ {
        self.reply("workDone")
        self.stop()
      }
      case replyTo: Channel[Any] ⇒ {
        replyTo ! "complexReply"
      }
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

class TestActorRefSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {

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
        val a = TestActorRef(new Actor {
          val nested = TestActorRef(new Actor { def receive = { case _ ⇒ } }).start()
          def receive = { case _ ⇒ self reply nested }
        }).start()
        a must not be (null)
        val nested = (a !! "any").get.asInstanceOf[ActorRef]
        nested must not be (null)
        a must not be theSameInstanceAs(nested)
      }

      "used with ActorRef" in {
        val a = TestActorRef(new Actor {
          val nested = Actor.actorOf(new Actor { def receive = { case _ ⇒ } }).start()
          def receive = { case _ ⇒ self reply nested }
        }).start()
        a must not be (null)
        val nested = (a !! "any").get.asInstanceOf[ActorRef]
        nested must not be (null)
        a must not be theSameInstanceAs(nested)
      }

    }

    "support reply via channel" in {
      val serverRef = TestActorRef[ReplyActor].start()
      val clientRef = TestActorRef(new SenderActor(serverRef)).start()

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
      val a = TestActorRef[WorkerActor].start()
      intercept[ActorKilledException] {
        a !! PoisonPill
      }
      a must not be ('running)
      a must be('shutdown)
      assertThread
    }

    "restart when Kill:ed" in {
      counter = 2

      val boss = TestActorRef(new TActor {
        self.faultHandler = OneForOneStrategy(List(classOf[Throwable]), Some(2), Some(1000))
        val ref = TestActorRef(new TActor {
          def receiveT = { case _ ⇒ }
          override def preRestart(reason: Throwable) { counter -= 1 }
          override def postRestart(reason: Throwable) { counter -= 1 }
        }).start()
        self.dispatcher = CallingThreadDispatcher.global
        self link ref
        def receiveT = { case "sendKill" ⇒ ref ! Kill }
      }).start()

      val l = stopLog()
      boss ! "sendKill"
      startLog(l)

      counter must be(0)
      assertThread
    }

    "support futures" in {
      val a = TestActorRef[WorkerActor].start()
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
      }).start()
      ref ! "hallo"
      val actor = ref.underlyingActor
      actor.s must equal("hallo")
    }

    "set receiveTimeout to None" in {
      val a = TestActorRef[WorkerActor]
      a.receiveTimeout must be(None)
    }

    "set CallingThreadDispatcher" in {
      val a = TestActorRef[WorkerActor]
      a.dispatcher.getClass must be(classOf[CallingThreadDispatcher])
    }

    "warn about scheduled supervisor" in {
      val boss = Actor.actorOf(new Actor { def receive = { case _ ⇒ } }).start()
      val ref = TestActorRef[WorkerActor].start()

      val log = TestActorRef[Logger]
      EventHandler.addListener(log)
      boss link ref
      val la = log.underlyingActor
      la.count must be(1)
      la.msg must (include("supervisor") and include("CallingThreadDispatcher"))
      EventHandler.removeListener(log)
    }

    "proxy isDefinedAt/apply for the underlying actor" in {
      val ref = TestActorRef[WorkerActor].start()
      ref.isDefinedAt("work") must be(true)
      ref.isDefinedAt("sleep") must be(false)
      intercept[IllegalActorStateException] { ref("work") }
      val ch = Future.channel()
      ref ! ch
      val f = ch.future
      f must be('completed)
      f.get must be("complexReply")
    }

  }

  private def stopLog() = {
    val l = Actor.registry.actorsFor[EventHandler.DefaultListener]
    l foreach (EventHandler.removeListener(_))
    l
  }

  private def startLog(l: Array[ActorRef]) {
    l foreach { a ⇒ EventHandler.addListener(Actor.actorOf[EventHandler.DefaultListener]) }
  }

}
