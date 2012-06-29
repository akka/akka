package akka.testkit

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import scala.concurrent.util.Duration
import com.typesafe.config.Config

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TestTimeSpec extends AkkaSpec(Map("akka.test.timefactor" -> 2.0)) with BeforeAndAfterEach {

  "A TestKit" must {

    "correctly dilate times" taggedAs TimingTest in {
      val probe = TestProbe()
      val now = System.nanoTime
      intercept[AssertionError] { probe.awaitCond(false, Duration("1 second")) }
      val diff = System.nanoTime - now
      val target = (1000000000l * testKitSettings.TestTimeFactor).toLong
      diff must be > (target - 300000000l)
      diff must be < (target + 300000000l)
    }

  }

}
