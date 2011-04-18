/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.dispatch.Dispatchers
import akka.config.Supervision.{SupervisorConfig, OneForOneStrategy, Supervise, Permanent}
import Actor._

class SupervisorTreeSpec extends WordSpec with MustMatchers {

  var log = ""
  case object Die
  class Chainer(myId: String, a: Option[ActorRef] = None) extends Actor {
    self.address = myId
    self.lifeCycle = Permanent
    self.faultHandler = OneForOneStrategy(List(classOf[Exception]), 3, 1000)
    a.foreach(self.link(_))

    def receive = {
      case Die => throw new Exception(self.address + " is dying...")
    }

    override def preRestart(reason: Throwable) {
      log += self.address
    }
  }

  "In a 3 levels deep supervisor tree (linked in the constructor) we" should {

    "be able to kill the middle actor and see itself and its child restarted" in {
      log = "INIT"

      val lastActor   = actorOf(new Chainer("lastActor")).start
      val middleActor = actorOf(new Chainer("middleActor", Some(lastActor))).start
      val headActor   = actorOf(new Chainer("headActor",   Some(middleActor))).start

      middleActor ! Die
      Thread.sleep(100)
      log must equal ("INITmiddleActorlastActor")
    }
  }
}
