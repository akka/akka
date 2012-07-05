/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.util.duration._
import akka.testkit.Testing.sleepFor
import akka.config.Supervision.{ OneForOneStrategy }
import akka.actor._
import akka.dispatch.Future
import java.util.concurrent.{ TimeUnit, CountDownLatch }

object ActorRefSpec {

  val latch = TestLatch(4)

  class ReplyActor extends Actor {
    var replyTo: Channel[Any] = null

    def receive = {
      case "complexRequest" ⇒ {
        replyTo = self.channel
        val worker = Actor.actorOf[WorkerActor].start()
        worker ! "work"
      }
      case "complexRequest2" ⇒
        val worker = Actor.actorOf[WorkerActor].start()
        worker ! self.channel
      case "workDone"      ⇒ replyTo ! "complexReply"
      case "simpleRequest" ⇒ self.reply("simpleReply")
    }
  }

  class WorkerActor() extends Actor {
    def receive = {
      case "work" ⇒ {
        work
        self.reply("workDone")
        self.stop()
      }
      case replyTo: Channel[Any] ⇒ {
        work
        replyTo ! "complexReply"
      }
    }

    private def work {
      sleepFor(1 second)
    }
  }

  class SenderActor(replyActor: ActorRef) extends Actor {

    def receive = {
      case "complex"  ⇒ replyActor ! "complexRequest"
      case "complex2" ⇒ replyActor ! "complexRequest2"
      case "simple"   ⇒ replyActor ! "simpleRequest"
      case "complexReply" ⇒ {
        latch.countDown()
      }
      case "simpleReply" ⇒ {
        latch.countDown()
      }
    }
  }
}

class ActorRefSpec extends WordSpec with MustMatchers {
  import ActorRefSpec._

  "An ActorRef" must {

    "not allow Actors to be created outside of an actorOf" in {
      intercept[akka.actor.ActorInitializationException] {
        new Actor { def receive = { case _ ⇒ } }
        fail("shouldn't get here")
      }

      intercept[akka.actor.ActorInitializationException] {
        val a = Actor.actorOf(new Actor {
          val nested = new Actor { def receive = { case _ ⇒ } }
          def receive = { case _ ⇒ }
        }).start()
        fail("shouldn't get here")
      }
    }

    "support nested actorOfs" in {
      val a = Actor.actorOf(new Actor {
        val nested = Actor.actorOf(new Actor { def receive = { case _ ⇒ } }).start()
        def receive = { case _ ⇒ self reply nested }
      }).start()

      val nested = (a !! "any").get.asInstanceOf[ActorRef]
      a must not be null
      nested must not be null
      (a ne nested) must be === true
    }

    "support reply via channel" in {
      val serverRef = Actor.actorOf[ReplyActor].start()
      val clientRef = Actor.actorOf(new SenderActor(serverRef)).start()

      clientRef ! "complex"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      latch.await

      latch.reset

      clientRef ! "complex2"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      latch.await

      clientRef.stop()
      serverRef.stop()
    }

    "stop when sent a poison pill" in {
      val ref = Actor.actorOf(
        new Actor {
          def receive = {
            case 5    ⇒ self tryReply "five"
            case null ⇒ self tryReply "null"
          }
        }).start()

      val ffive: Future[String] = ref !!! 5
      val fnull: Future[String] = ref !!! null

      intercept[ActorKilledException] {
        ref !! PoisonPill
        fail("shouldn't get here")
      }

      ffive.resultOrException.get must be("five")
      fnull.resultOrException.get must be("null")

      ref.isRunning must be(false)
      ref.isShutdown must be(true)
    }

    "restart when Kill:ed" in {
      val latch = new CountDownLatch(2)

      val boss = Actor.actorOf(new Actor {
        self.faultHandler = OneForOneStrategy(List(classOf[Throwable]), scala.Some(2), scala.Some(1000))

        val ref = Actor.actorOf(
          new Actor {
            def receive = { case _ ⇒ }
            override def preRestart(reason: Throwable) = latch.countDown()
            override def postRestart(reason: Throwable) = latch.countDown()
          }).start()

        self link ref

        protected def receive = { case "sendKill" ⇒ ref ! Kill }
      }).start()

      boss ! "sendKill"
      latch.await(5, TimeUnit.SECONDS) must be === true
    }
  }
}
