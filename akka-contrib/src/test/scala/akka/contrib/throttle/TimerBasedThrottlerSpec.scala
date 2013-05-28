/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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
    shutdown(system)
  }

  "A throttler" must {
    def println(a: Any) = ()
    "pass the ScalaDoc class documentation example program" in {
      //#demo-code
      // A simple actor that prints whatever it receives
      val printer = system.actorOf(Props(new Actor {
        def receive = {
          case x ⇒ println(x)
        }
      }))
      // The throttler for this example, setting the rate
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler],
        3 msgsPer (1.second.dilated)))
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
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], 3 msgsPer (1.second.dilated)))
      1 to 6 foreach { throttler ! _ }
      expectNoMsg(1 second)
      throttler ! SetTarget(Some(echo))
      within(2.5 seconds) {
        1 to 6 foreach { expectMsg(_) }
      }
    }

    "send messages after a `SetTarget(None)` pause" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], 3 msgsPer (1.second.dilated)))
      throttler ! SetTarget(Some(echo))
      1 to 3 foreach { throttler ! _ }
      throttler ! SetTarget(None)
      within(1 second) {
        1 to 3 foreach { expectMsg(_) }
        expectNoMsg()
      }
      expectNoMsg(1 second)
      throttler ! SetTarget(Some(echo))
      4 to 7 foreach { throttler ! _ }
      within(0.5 seconds, 1.5 seconds) {
        4 to 7 foreach { expectMsg(_) }
      }
    }

    "keep messages when the target is set to None" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], 3 msgsPer (1.second.dilated)))
      throttler ! SetTarget(Some(echo))
      1 to 7 foreach { throttler ! _ }
      throttler ! SetTarget(None)
      within(1 second) {
        1 to 3 foreach { expectMsg(_) }
        expectNoMsg()
      }
      expectNoMsg(1 second)
      throttler ! SetTarget(Some(echo))
      within(0.5 seconds, 1.5 seconds) {
        4 to 7 foreach { expectMsg(_) }
      }
    }

    "respect the rate (3 msg/s)" in within(1.5 seconds, 2.5 seconds) {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], 3 msgsPer (1.second.dilated)))
      throttler ! SetTarget(Some(echo))
      1 to 7 foreach { throttler ! _ }
      1 to 7 foreach { expectMsg(_) }
    }

    "respect the rate (4 msg/s)" in within(1.5 seconds, 2.5 seconds) {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], 4 msgsPer (1.second.dilated)))
      throttler ! SetTarget(Some(echo))
      1 to 9 foreach { throttler ! _ }
      1 to 9 foreach { expectMsg(_) }
    }
  }
}
