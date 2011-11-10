package akka.testkit

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import akka.util.Duration
import akka.config.Configuration

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TestTimeSpec extends AkkaSpec(Configuration("akka.test.timefactor" -> 2.0)) with BeforeAndAfterEach {

  "A TestKit" must {

    "correctly dilate times" in {
      val probe = TestProbe()
      val now = System.nanoTime
      intercept[AssertionError] { probe.awaitCond(false, Duration("1 second")) }
      val diff = System.nanoTime - now
      diff must be > 1700000000l
      diff must be < 3000000000l
    }

  }

}
