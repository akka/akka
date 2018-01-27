package akka.actor.typed

import akka.testkit.typed.{ TestInbox, TestKit }
import akka.util.Timeout
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/**
 * Helper trait to include standard traits for typed tests
 */
trait TypedAkkaSpec extends WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures with TypeCheckedTripleEquals {
  implicit val akkaPatience = PatienceConfig(3.seconds, Span(100, org.scalatest.time.Millis))
  implicit val timeout = Timeout(3.seconds)

  def assertEmpty(inboxes: TestInbox[_]*): Unit = {
    inboxes foreach (i ⇒ withClue(s"inbox $i had messages")(i.hasMessages should be(false)))
  }

}

/**
 * Helper that also shuts down the actor system if using [[TestKit]]
 */
trait TypedAkkaSpecWithShutdown extends TypedAkkaSpec {
  self: TestKit ⇒
  override protected def afterAll(): Unit = shutdown()
}

class TestException(msg: String) extends RuntimeException(msg) with NoStackTrace
