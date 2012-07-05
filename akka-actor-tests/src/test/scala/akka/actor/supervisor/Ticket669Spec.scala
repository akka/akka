/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.supervisor

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.actor._
import akka.config.Supervision._

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers

class Ticket669Spec extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import Ticket669Spec._

  override def afterAll() { Actor.registry.shutdownAll() }

  "A supervised actor with lifecycle PERMANENT" should {
    "be able to reply on failure during preRestart" in {
      val latch = new CountDownLatch(1)
      val sender = Actor.actorOf(new Sender(latch)).start()

      val supervised = Actor.actorOf[Supervised]
      val supervisor = Supervisor(SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 5, 10000),
        Supervise(supervised, Permanent) :: Nil))

      supervised.!("test")(Some(sender))
      latch.await(5, TimeUnit.SECONDS) must be(true)
    }

    "be able to reply on failure during postStop" in {
      val latch = new CountDownLatch(1)
      val sender = Actor.actorOf(new Sender(latch)).start()

      val supervised = Actor.actorOf[Supervised]
      val supervisor = Supervisor(SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 5, 10000),
        Supervise(supervised, Temporary) :: Nil))

      supervised.!("test")(Some(sender))
      latch.await(5, TimeUnit.SECONDS) must be(true)
    }
  }
}

object Ticket669Spec {
  class Sender(latch: CountDownLatch) extends Actor {
    def receive = {
      case "failure1" ⇒ latch.countDown()
      case "failure2" ⇒ latch.countDown()
      case _          ⇒ {}
    }
  }

  class Supervised extends Actor {
    def receive = {
      case msg ⇒ throw new Exception("test")
    }

    override def preRestart(reason: scala.Throwable) {
      self.tryReply("failure1")
    }

    override def postStop() {
      self.tryReply("failure2")
    }
  }
}