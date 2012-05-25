package docs.testkit

import org.specs2.Specification
import org.specs2.specification.{ Step, Scope }

import akka.actor.{ Props, ActorSystem, Actor }
import akka.testkit.{ TestKit, ImplicitSender }

class Specs2DemoAcceptance extends Specification {
  def is =

    "This is a specification of basic TestKit interop" ^
      p ^
      "A TestKit should" ^
      "work properly with Specs2 acceptance tests" ! e1 ^
      "correctly convert durations" ! e2 ^
      Step(system.shutdown()) ^ end // do not forget to shutdown!

  val system = ActorSystem()

  // an alternative to mixing in NoTimeConversions
  implicit def d2d(d: org.specs2.time.Duration): akka.util.FiniteDuration =
    akka.util.Duration(d.inMilliseconds, "millis")

  def e1 = new TestKit(system) with Scope with ImplicitSender {
    within(1 second) {
      system.actorOf(Props(new Actor {
        def receive = { case x ⇒ sender ! x }
      })) ! "hallo"

      expectMsgType[String] must be equalTo "hallo"
    }
  }

  def e2 = ((1 second): akka.util.Duration).toMillis must be equalTo 1000
}
