/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.actor._
import akka.config.Supervision._
import akka.testkit.{ filterEvents, EventFilter }
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers

class Ticket669Spec extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import Ticket669Spec._

  override def beforeAll = Thread.interrupted() //remove interrupted status.

  override def afterAll = {
    Actor.registry.local.shutdownAll
    akka.event.EventHandler.start()
  }

  "A supervised actor with lifecycle PERMANENT" should {
    "be able to reply on failure during preRestart" in {
      filterEvents(EventFilter[Exception]("test")) {
        val latch = new CountDownLatch(1)
        val sender = Actor.actorOf(new Sender(latch))

        val supervised = Actor.actorOf[Supervised]
        val supervisor = Supervisor(SupervisorConfig(
          AllForOnePermanentStrategy(List(classOf[Exception]), 5, 10000),
          Supervise(supervised, Permanent) :: Nil))

        supervised.!("test")(Some(sender))
        latch.await(5, TimeUnit.SECONDS) must be(true)
      }
    }

    "be able to reply on failure during postStop" in {
      filterEvents(EventFilter[Exception]("test")) {
        val latch = new CountDownLatch(1)
        val sender = Actor.actorOf(new Sender(latch))

        val supervised = Actor.actorOf[Supervised]
        val supervisor = Supervisor(SupervisorConfig(
          AllForOnePermanentStrategy(List(classOf[Exception]), 5, 10000),
          Supervise(supervised, Temporary) :: Nil))

        supervised.!("test")(Some(sender))
        latch.await(5, TimeUnit.SECONDS) must be(true)
      }
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

    override def preRestart(reason: scala.Throwable, msg: Option[Any]) {
      self.tryReply("failure1")
    }

    override def postStop() {
      self.tryReply("failure2")
    }
  }
}
