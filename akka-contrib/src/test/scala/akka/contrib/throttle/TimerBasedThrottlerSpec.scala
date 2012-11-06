/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.throttle

import language.postfixOps
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import akka.contrib.throttle.Throttler._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import akka.testkit._

object TimerBasedThrottlerSpec {
  class EchoActor extends Actor {
    def receive = {
      case x ⇒ sender ! x
    }
  }
}

@RunWith(classOf[JUnitRunner])
class TimerBasedThrottlerSpec extends TestKit(ActorSystem("TimerBasedThrottlerSpec")) with ImplicitSender
  with WordSpec with MustMatchers with BeforeAndAfterAll {

  override def afterAll {
    system.shutdown()
  }

  "A throttler" must {

    "must pass the ScalaDoc class documentation example program" in {
      //#demo-code
      // A simple actor that prints whatever it receives
      val printer = system.actorOf(Props(new Actor {
        def receive = {
          case x ⇒ println(x)
        }
      }))
      // The throttler for this example, setting the rate
      val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second.dilated))))
      // Set the target 
      throttler ! SetTarget(Some(printer))
      // These three messages will be sent to the echoer immediately
      throttler ! "1"
      throttler ! "2"
      throttler ! "3"
      // These two will wait until a second has passed
      throttler ! "4"
      throttler ! "5"
      //#demo-code
    }

    "keep messages until a target is set" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second.dilated))))
      throttler ! "1"
      throttler ! "2"
      throttler ! "3"
      throttler ! "4"
      throttler ! "5"
      throttler ! "6"
      expectNoMsg(1 second)
      throttler ! SetTarget(Some(echo))
      within(2 seconds) {
        expectMsg("1")
        expectMsg("2")
        expectMsg("3")
        expectMsg("4")
        expectMsg("5")
        expectMsg("6")
      }
    }

    "send messages after a `SetTarget(None)` pause" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second.dilated))))
      throttler ! SetTarget(Some(echo))
      throttler ! "1"
      throttler ! "2"
      throttler ! "3"
      throttler ! SetTarget(None)
      within(1 second) {
        expectMsg("1")
        expectMsg("2")
        expectMsg("3")
        expectNoMsg()
      }
      expectNoMsg(1 second)
      throttler ! SetTarget(Some(echo))
      throttler ! "4"
      throttler ! "5"
      throttler ! "6"
      throttler ! "7"
      within(1 seconds) {
        expectMsg("4")
        expectMsg("5")
        expectMsg("6")
        expectNoMsg()
      }
      within(1 second) {
        expectMsg("7")
      }
    }

    "keep messages when the target is set to None" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second.dilated))))
      throttler ! SetTarget(Some(echo))
      throttler ! "1"
      throttler ! "2"
      throttler ! "3"
      throttler ! "4"
      throttler ! "5"
      throttler ! "6"
      throttler ! "7"
      throttler ! SetTarget(None)
      within(1 second) {
        expectMsg("1")
        expectMsg("2")
        expectMsg("3")
        expectNoMsg()
      }
      expectNoMsg(1 second)
      throttler ! SetTarget(Some(echo))
      within(1 seconds) {
        expectMsg("4")
        expectMsg("5")
        expectMsg("6")
        expectNoMsg()
      }
      within(1 second) {
        expectMsg("7")
      }
    }

    "respect the rate (3 msg/s)" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second.dilated))))
      throttler ! SetTarget(Some(echo))
      throttler ! "1"
      throttler ! "2"
      throttler ! "3"
      throttler ! "4"
      throttler ! "5"
      throttler ! "6"
      throttler ! "7"
      within(1 second) {
        expectMsg("1")
        expectMsg("2")
        expectMsg("3")
        expectNoMsg()
      }
      within(1 second) {
        expectMsg("4")
        expectMsg("5")
        expectMsg("6")
        expectNoMsg()
      }
      within(1 second) {
        expectMsg("7")
      }
    }

    "respect the rate (4 msg/s)" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(new TimerBasedThrottler(4 msgsPer (1.second.dilated))))
      throttler ! SetTarget(Some(echo))
      throttler ! "1"
      throttler ! "2"
      throttler ! "3"
      throttler ! "4"
      throttler ! "5"
      throttler ! "6"
      throttler ! "7"
      throttler ! "8"
      throttler ! "9"
      within(1 second) {
        expectMsg("1")
        expectMsg("2")
        expectMsg("3")
        expectMsg("4")
        expectNoMsg()
      }
      within(1 second) {
        expectMsg("5")
        expectMsg("6")
        expectMsg("7")
        expectMsg("8")
        expectNoMsg()
      }
      within(1 second) {
        expectMsg("9")
      }
    }
  }
}