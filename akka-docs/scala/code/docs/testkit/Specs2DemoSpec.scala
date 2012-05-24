package docs.testkit

import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import akka.actor.{ Props, ActorSystem, Actor }
import akka.testkit.{ TestKit, ImplicitSender }

class Specs2DemoUnitSpec extends Specification {

  val system = ActorSystem()

  implicit def d2d(d: org.specs2.time.Duration): akka.util.FiniteDuration =
    akka.util.Duration(d.inMilliseconds, "millis")

  /*
   * this is needed if different test cases would clash when run concurrently,
   * e.g. when creating specifically named top-level actors
   */
  sequential

  "A TestKit" should {
    "work properly with Specs2 unit tests" in
      new TestKit(system) with Scope with ImplicitSender {
        within(1 second) {
          system.actorOf(Props(new Actor {
            def receive = { case x ⇒ sender ! x }
          })) ! "hallo"

          expectMsgType[String] must be equalTo "hallo"
        }
      }
  }
}
