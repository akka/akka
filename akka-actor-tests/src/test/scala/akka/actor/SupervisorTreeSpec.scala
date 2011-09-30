/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.util.duration._
import akka.testkit.Testing.sleepFor
import akka.testkit.{ EventFilter, filterEvents, filterException }
import akka.dispatch.Dispatchers
import akka.actor.Actor._

class SupervisorTreeSpec extends WordSpec with MustMatchers {

  var log = ""
  case object Die
  class Chainer(a: Option[ActorRef]) extends Actor {
    a.foreach(self.link(_))

    def receive = {
      case Die ⇒ throw new Exception(self.address + " is dying...")
    }

    override def preRestart(reason: Throwable, msg: Option[Any]) {
      log += self.address
    }
  }

  "In a 3 levels deep supervisor tree (linked in the constructor) we" must {

    "be able to kill the middle actor and see itself and its child restarted" in {
      filterException[Exception] {
        log = "INIT"

        val p = Props.default.withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, 1000))
        val lastActor = actorOf(p.withCreator(new Chainer(None)), "lastActor")
        val middleActor = actorOf(p.withCreator(new Chainer(Some(lastActor))), "middleActor")
        val headActor = actorOf(p.withCreator(new Chainer(Some(middleActor))), "headActor")

        middleActor ! Die
        sleepFor(500 millis)
        log must equal("INITmiddleActorlastActor")
      }
    }
  }
}
