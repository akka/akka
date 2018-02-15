package akka.actor.typed

import akka.testkit.typed.TestInbox
import akka.testkit.typed.scaladsl.TestKit
import akka.util.Timeout
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.time.Span
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/**
 * Helper trait to include standard traits for typed tests
 */
trait TypedAkkaSpec extends WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures
  with TypeCheckedTripleEquals with Eventually {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(3.seconds, Span(100, org.scalatest.time.Millis))

  def assertEmpty(inboxes: TestInbox[_]*): Unit = {
    inboxes foreach (i ⇒ withClue(s"inbox $i had messages")(i.hasMessages should be(false)))
  }

}

/**
 * Helper that also shuts down the actor system if using [[TestKit]]
 */
trait TypedAkkaSpecWithShutdown extends TypedAkkaSpec {
  self: TestKit ⇒
  override protected def afterAll(): Unit = shutdownTestKit()
}

class TestException(msg: String) extends RuntimeException(msg) with NoStackTrace
