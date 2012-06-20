package docs.testkit

import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import org.specs2.time.NoTimeConversions

import akka.actor.{ Props, ActorSystem, Actor }
import akka.testkit.{ TestKit, ImplicitSender }
import akka.util.duration._

class Specs2DemoUnitSpec extends Specification with NoTimeConversions {

  implicit val system = ActorSystem()

  /*
   * this is needed if different test cases would clash when run concurrently,
   * e.g. when creating specifically named top-level actors; leave out otherwise
   */
  sequential

  "A TestKit" should {
    "work properly with Specs2 unit tests" in new test {
      within(1 second) {
        system.actorOf(Props(new Actor {
          def receive = { case x ⇒ sender ! x }
        })) ! "hallo"

        expectMsgType[String] must be equalTo "hallo"
      }
    }
  }

  step(system.shutdown) // do not forget to shutdown!
}

class test(implicit system: ActorSystem) extends ImplicitSender with Scope
