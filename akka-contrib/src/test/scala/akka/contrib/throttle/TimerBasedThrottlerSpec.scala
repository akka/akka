/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll
import akka.testkit._

object TimerBasedThrottlerSpec {
  def println(a: Any) = ()

  //#demo-code
  // A simple actor that prints whatever it receives
  class PrintActor extends Actor {
    def receive = {
      case x ⇒ println(x)
    }
  }

  //#demo-code
}

class TimerBasedThrottlerSpec extends TestKit(ActorSystem("TimerBasedThrottlerSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  import TimerBasedThrottlerSpec._

  override def afterAll {
    shutdown()
  }

  "A throttler" must {
    def println(a: Any) = ()
    "pass the ScalaDoc class documentation example program" in {
      //#demo-code
      val printer = system.actorOf(Props[PrintActor])
      // The throttler for this example, setting the rate
      val throttler = system.actorOf(Props(
        classOf[TimerBasedThrottler],
        3 msgsPer 1.second))
      // Set the target
      throttler ! SetTarget(Some(printer))
      // These three messages will be sent to the target immediately
      throttler ! "1"
      throttler ! "2"
      throttler ! "3"
      // These two will wait until a second has passed
      throttler ! "4"
      throttler ! "5"
      //#demo-code
    }

    "keep messages until a target is set" in {
      val echo = system.actorOf(TestActors.echoActorProps)
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], 3 msgsPer (1.second.dilated)))
      1 to 6 foreach { throttler ! _ }
      expectNoMsg(1 second)
      throttler ! SetTarget(Some(echo))
      within(2.5 seconds) {
        1 to 6 foreach { expectMsg(_) }
      }
    }

    "send messages after a `SetTarget(None)` pause" in {
      val echo = system.actorOf(TestActors.echoActorProps)
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
      within(1.5 seconds) {
        4 to 7 foreach { expectMsg(_) }
      }
    }

    "keep messages when the target is set to None" in {
      val echo = system.actorOf(TestActors.echoActorProps)
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
      within(1.5 seconds) {
        4 to 7 foreach { expectMsg(_) }
      }
    }

    "respect the rate (3 msg/s)" in within(1.5 seconds, 2.5 seconds) {
      val echo = system.actorOf(TestActors.echoActorProps)
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], 3 msgsPer (1.second.dilated)))
      throttler ! SetTarget(Some(echo))
      1 to 7 foreach { throttler ! _ }
      1 to 7 foreach { expectMsg(_) }
    }

    "respect the rate (4 msg/s)" in within(1.5 seconds, 2.5 seconds) {
      val echo = system.actorOf(TestActors.echoActorProps)
      val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], 4 msgsPer (1.second.dilated)))
      throttler ! SetTarget(Some(echo))
      1 to 9 foreach { throttler ! _ }
      1 to 9 foreach { expectMsg(_) }
    }
  }
}
