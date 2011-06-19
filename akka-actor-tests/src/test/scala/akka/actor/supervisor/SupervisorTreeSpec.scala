/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.util.duration._
import akka.testkit.Testing.sleepFor
import akka.dispatch.Dispatchers
import akka.config.Supervision.{ SupervisorConfig, OneForOneStrategy, Supervise, Permanent }
import Actor._

class SupervisorTreeSpec extends WordSpec with MustMatchers {
  var log = ""
  case object Die

  class Chainer(myId: String, a: Option[ActorRef] = None) extends Actor {
    self.id = myId
    self.lifeCycle = Permanent
    self.faultHandler = OneForOneStrategy(List(classOf[Exception]), 3, 1000)
    a.foreach(self.link(_))

    def receive = {
      case Die ⇒ throw new Exception(self.id + " is dying...")
    }

    override def preRestart(reason: Throwable) {
      log += self.id
    }
  }

  "In a 3 levels deep supervisor tree (linked in the constructor) we" must {

    "be able to kill the middle actor and see itself and its child restarted" in {
      log = "INIT"

      val lastActor = actorOf(new Chainer("lastActor")).start()
      val middleActor = actorOf(new Chainer("middleActor", Some(lastActor))).start()
      val headActor = actorOf(new Chainer("headActor", Some(middleActor))).start()

      middleActor ! Die
      sleepFor(500 millis)
      log must equal("INITmiddleActorlastActor")
    }
  }
}
