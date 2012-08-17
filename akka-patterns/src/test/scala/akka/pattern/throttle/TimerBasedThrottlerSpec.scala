package akka.pattern.throttle

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.TestKit
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import akka.testkit.ImplicitSender
import scala.concurrent.util.duration._
import akka.pattern.throttle.Throttler._

object TimerBasedThrottlerSpec {
  class EchoActor extends Actor {
    def receive = {
      case x ⇒ sender ! x
    }
  }
}

@RunWith(classOf[JUnitRunner])
class TimerBasedThrottlerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpec with MustMatchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("TimerBasedThrottlerSpec"))

  override def afterAll {
    system.shutdown()
  }

  "A throttler" must {

    "must pass the ScalaDoc class documentation example prgoram" in {
      // A simple actor that prints whatever it receives
      val printer = system.actorOf(Props(new Actor {
        def receive = {
          case x ⇒ println(x)
        }
      }))
      // The throttler for this example, setting the rate
      val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second))))
      // Set the target 
      throttler ! SetTarget(Some(printer))
      // These three messages will be sent to the echoer immediately
      throttler ! Queue("1")
      throttler ! Queue("2")
      throttler ! Queue("3")
      // These two will wait until a second has passed
      throttler ! Queue("4")
      throttler ! Queue("5")
    }

    "keep messages until a target is set" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second))))
      throttler ! Queue("1")
      throttler ! Queue("2")
      throttler ! Queue("3")
      throttler ! Queue("4")
      throttler ! Queue("5")
      throttler ! Queue("6")
      expectNoMsg(1.second)
      throttler ! SetTarget(Some(echo))
      within(2.seconds) {
        expectMsg("1")
        expectMsg("2")
        expectMsg("3")
        expectMsg("4")
        expectMsg("5")
        expectMsg("6")
      }
    }

    "respect the rate (3 msg/s)" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second))))
      throttler ! SetTarget(Some(echo))
      throttler ! Queue("1")
      throttler ! Queue("2")
      throttler ! Queue("3")
      throttler ! Queue("4")
      throttler ! Queue("5")
      throttler ! Queue("6")
      throttler ! Queue("7")
      within(1.second) {
        expectMsg("1")
        expectMsg("2")
        expectMsg("3")
        expectNoMsg(remaining)
      }
      within(1.second) {
        expectMsg("4")
        expectMsg("5")
        expectMsg("6")
        expectNoMsg(remaining)
      }
      within(1.second) {
        expectMsg("7")
      }
    }

    "respect the rate (4 msg/s)" in {
      val echo = system.actorOf(Props[TimerBasedThrottlerSpec.EchoActor])
      val throttler = system.actorOf(Props(new TimerBasedThrottler(4 msgsPer (1.second))))
      throttler ! SetTarget(Some(echo))
      throttler ! Queue("1")
      throttler ! Queue("2")
      throttler ! Queue("3")
      throttler ! Queue("4")
      throttler ! Queue("5")
      throttler ! Queue("6")
      throttler ! Queue("7")
      throttler ! Queue("8")
      throttler ! Queue("9")
      within(1.second) {
        expectMsg("1")
        expectMsg("2")
        expectMsg("3")
        expectMsg("4")
        expectNoMsg(remaining)
      }
      within(1.second) {
        expectMsg("5")
        expectMsg("6")
        expectMsg("7")
        expectMsg("8")
        expectNoMsg(remaining)
      }
      within(1.second) {
        expectMsg("9")
      }
    }
  }
}