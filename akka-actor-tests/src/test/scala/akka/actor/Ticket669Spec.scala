/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.actor._
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit.{ TestKit, filterEvents, EventFilter }

class Ticket669Spec extends WordSpec with MustMatchers with BeforeAndAfterAll with TestKit {
  import Ticket669Spec._

  override def beforeAll = Thread.interrupted() //remove interrupted status.

  override def afterAll = {
    //    Actor.registry.local.shutdownAll
    akka.event.EventHandler.start()
  }

  "A supervised actor with lifecycle PERMANENT" should {
    "be able to reply on failure during preRestart" in {
      filterEvents(EventFilter[Exception]("test")) {
        val supervisor = Supervisor(AllForOneStrategy(List(classOf[Exception]), 5, 10000))
        val supervised = Actor.actorOf(Props[Supervised].withSupervisor(supervisor))

        supervised.!("test")(Some(testActor))
        expectMsg("failure1")
        supervisor.stop()
      }
    }

    "be able to reply on failure during postStop" in {
      filterEvents(EventFilter[Exception]("test")) {
        val supervisor = Supervisor(AllForOneStrategy(List(classOf[Exception]), Some(0), None))
        val supervised = Actor.actorOf(Props[Supervised].withSupervisor(supervisor))

        supervised.!("test")(Some(testActor))
        expectMsg("failure2")
        supervisor.stop()
      }
    }
  }
}

object Ticket669Spec {
  class Supervised extends Actor {
    def receive = {
      case msg ⇒ throw new Exception("test")
    }

    override def preRestart(reason: scala.Throwable, msg: Option[Any]) {
      tryReply("failure1")
    }

    override def postStop() {
      tryReply("failure2")
    }
  }
}
