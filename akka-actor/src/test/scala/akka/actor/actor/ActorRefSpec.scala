/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.actor._
import akka.dispatch.Future
import java.util.concurrent.{CountDownLatch, TimeUnit}

object ActorRefSpec {

  var latch = new CountDownLatch(4)

  class ReplyActor extends Actor {
    var replyTo: Channel[Any] = null

    def receive = {
      case "complexRequest" => {
        replyTo = self.channel
        val worker = Actor.actorOf[WorkerActor].start
        worker ! "work"
      }
      case "complexRequest2" =>
        val worker = Actor.actorOf[WorkerActor].start
        worker ! self.channel
      case "workDone" => replyTo ! "complexReply"
      case "simpleRequest" => self.reply("simpleReply")
    }
  }

  class WorkerActor() extends Actor {
    def receive = {
      case "work" => {
        work
        self.reply("workDone")
        self.stop
      }
      case replyTo: Channel[Any] => {
        work
        replyTo ! "complexReply"
      }
    }

    private def work {
      Thread.sleep(1000)
    }
  }

  class SenderActor(replyActor: ActorRef) extends Actor {

    def receive = {
      case "complex" => replyActor ! "complexRequest"
      case "complex2" => replyActor ! "complexRequest2"
      case "simple" => replyActor ! "simpleRequest"
      case "complexReply" => {
        println("got complex reply")
        latch.countDown
      }
      case "simpleReply" => {
        println("got simple reply")
        latch.countDown
      }
    }
  }
}

@RunWith(classOf[JUnitRunner])
class ActorRefSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  import ActorRefSpec._

  describe("ActorRef") {
    it("should support to reply via channel") {
      val serverRef = Actor.actorOf[ReplyActor].start
      val clientRef = Actor.actorOf(new SenderActor(serverRef)).start

      clientRef ! "complex"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"
      assert(latch.await(4L, TimeUnit.SECONDS))
      latch = new CountDownLatch(4)
      clientRef ! "complex2"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"
      assert(latch.await(4L, TimeUnit.SECONDS))
      clientRef.stop
      serverRef.stop
    }

    it("should stop when sent a poison pill") {
      val ref = Actor.actorOf(
        new Actor {
          def receive = {
            case 5 => self reply_? "five"
            case null => self reply_? "null"
          }
        }
      ).start

      val ffive: Future[String] = ref !!! 5
      val fnull: Future[String] = ref !!! null

      intercept[ActorKilledException] {
        ref !! PoisonPill
        fail("shouldn't get here")
      }

      assert(ffive.resultOrException.get == "five")
      assert(fnull.resultOrException.get == "null")

      assert(ref.isRunning == false)
      assert(ref.isShutdown == true)
    }

    it("should support spawning via manifest, class, and factory function") {
      class SimpleActor extends Actor {
        def receive = {
          case _ =>
        }
      }

      Actor.actorOf(
        new Actor {
          override def preStart {
            self.spawn[SimpleActor]
            self.spawn(classOf[SimpleActor])
            self.spawn(new SimpleActor)

            self.spawnLink[SimpleActor]
            self.spawnLink(classOf[SimpleActor])
            self.spawnLink(new SimpleActor)
          }
          def receive = {
            case _ =>
          }
        }
      )
    }
  }
}
