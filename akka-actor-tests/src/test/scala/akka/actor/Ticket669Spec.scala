/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import language.postfixOps

import org.scalatest.BeforeAndAfterAll
import akka.testkit.{ filterEvents, EventFilter }
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.testkit.DefaultTimeout
import scala.concurrent.Await
import akka.pattern.ask
import scala.concurrent.duration._

class Ticket669Spec extends AkkaSpec with BeforeAndAfterAll with ImplicitSender with DefaultTimeout {
  import Ticket669Spec._

  // TODO: does this really make sense?
  override def atStartup(): Unit = {
    Thread.interrupted() //remove interrupted status.
  }

  "A supervised actor with lifecycle PERMANENT" should {
    "be able to reply on failure during preRestart" in {
      filterEvents(EventFilter[Exception]("test", occurrences = 1)) {
        val supervisor =
          system.actorOf(Props(new Supervisor(AllForOneStrategy(5, 10 seconds)(List(classOf[Exception])))))
        val supervised = Await.result((supervisor ? Props[Supervised]).mapTo[ActorRef], timeout.duration)

        supervised.!("test")(testActor)
        expectMsg("failure1")
        system.stop(supervisor)
      }
    }

    "be able to reply on failure during postStop" in {
      filterEvents(EventFilter[Exception]("test", occurrences = 1)) {
        val supervisor =
          system.actorOf(Props(new Supervisor(AllForOneStrategy(maxNrOfRetries = 0)(List(classOf[Exception])))))
        val supervised = Await.result((supervisor ? Props[Supervised]).mapTo[ActorRef], timeout.duration)

        supervised.!("test")(testActor)
        expectMsg("failure2")
        system.stop(supervisor)
      }
    }
  }
}

object Ticket669Spec {
  class Supervised extends Actor {
    def receive = {
      case msg => throw new Exception("test")
    }

    override def preRestart(reason: scala.Throwable, msg: Option[Any]): Unit = {
      sender() ! "failure1"
    }

    override def postStop(): Unit = {
      sender() ! "failure2"
    }
  }
}
