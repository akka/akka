package akka.testkit

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import akka.util.Duration

class TestTimeSpec extends TestKit with WordSpec with MustMatchers with BeforeAndAfterEach {

  val tf = Duration.timeFactor

  override def beforeEach {
    val f = Duration.getClass.getDeclaredField("timeFactor")
    f.setAccessible(true)
    f.setDouble(Duration, 2.0)
  }

  override def afterEach {
    val f = Duration.getClass.getDeclaredField("timeFactor")
    f.setAccessible(true)
    f.setDouble(Duration, tf)
  }

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
