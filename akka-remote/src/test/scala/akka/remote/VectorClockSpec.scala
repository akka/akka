package akka.remote

import java.net.InetSocketAddress
import akka.testkit.AkkaSpec

class VectorClockSpec extends AkkaSpec {
  import VectorClock._

  "A VectorClock" must {

    "have zero versions when created" in {
      val clock = VectorClock()
      clock.versions must be(Vector())
    }

    "be able to add Entry if non-existing" in {
      val clock1 = VectorClock()
      clock1.versions must be(Vector())
      val clock2 = clock1.increment(1, System.currentTimeMillis)
      val clock3 = clock2.increment(2, System.currentTimeMillis)

      clock3.versions must be(Vector(Entry(1, 1), Entry(2, 1)))
    }

    "be able to increment version of existing Entry" in {
      val clock1 = VectorClock()
      val clock2 = clock1.increment(1, System.currentTimeMillis)
      val clock3 = clock2.increment(2, System.currentTimeMillis)
      val clock4 = clock3.increment(1, System.currentTimeMillis)
      val clock5 = clock4.increment(2, System.currentTimeMillis)
      val clock6 = clock5.increment(2, System.currentTimeMillis)

      clock6.versions must be(Vector(Entry(1, 2), Entry(2, 3)))
    }

    "The empty clock should not happen before itself" in {
      val clock1 = VectorClock()
      val clock2 = VectorClock()

      clock1.compare(clock2) must not be (Concurrent)
    }

    "not happen before an identical clock" in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1.increment(1, System.currentTimeMillis)
      val clock3_1 = clock2_1.increment(2, System.currentTimeMillis)
      val clock4_1 = clock3_1.increment(1, System.currentTimeMillis)

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2.increment(1, System.currentTimeMillis)
      val clock3_2 = clock2_2.increment(2, System.currentTimeMillis)
      val clock4_2 = clock3_2.increment(1, System.currentTimeMillis)

      clock4_1.compare(clock4_2) must not be (Concurrent)
    }

    "happen before an identical clock with a single additional event" in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1.increment(1, System.currentTimeMillis)
      val clock3_1 = clock2_1.increment(2, System.currentTimeMillis)
      val clock4_1 = clock3_1.increment(1, System.currentTimeMillis)

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2.increment(1, System.currentTimeMillis)
      val clock3_2 = clock2_2.increment(2, System.currentTimeMillis)
      val clock4_2 = clock3_2.increment(1, System.currentTimeMillis)
      val clock5_2 = clock4_2.increment(3, System.currentTimeMillis)

      clock4_1.compare(clock5_2) must be(Before)
    }

    "Two clocks with different events should be concurrent: 1" in {
      var clock1_1 = VectorClock()
      val clock2_1 = clock1_1.increment(1, System.currentTimeMillis)

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2.increment(2, System.currentTimeMillis)

      clock2_1.compare(clock2_2) must be(Concurrent)
    }

    "Two clocks with different events should be concurrent: 2" in {
      val clock1_3 = VectorClock()
      val clock2_3 = clock1_3.increment(1, System.currentTimeMillis)
      val clock3_3 = clock2_3.increment(2, System.currentTimeMillis)
      val clock4_3 = clock3_3.increment(1, System.currentTimeMillis)

      val clock1_4 = VectorClock()
      val clock2_4 = clock1_4.increment(1, System.currentTimeMillis)
      val clock3_4 = clock2_4.increment(1, System.currentTimeMillis)
      val clock4_4 = clock3_4.increment(3, System.currentTimeMillis)

      clock4_3.compare(clock4_4) must be(Concurrent)
    }

    ".." in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1.increment(2, System.currentTimeMillis)
      val clock3_1 = clock2_1.increment(2, System.currentTimeMillis)

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2.increment(1, System.currentTimeMillis)
      val clock3_2 = clock2_2.increment(2, System.currentTimeMillis)
      val clock4_2 = clock3_2.increment(2, System.currentTimeMillis)
      val clock5_2 = clock4_2.increment(3, System.currentTimeMillis)

      clock3_1.compare(clock5_2) must be(Before)
    }

    "..." in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1.increment(1, System.currentTimeMillis)
      val clock3_1 = clock2_1.increment(2, System.currentTimeMillis)
      val clock4_1 = clock3_1.increment(2, System.currentTimeMillis)
      val clock5_1 = clock4_1.increment(3, System.currentTimeMillis)

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2.increment(2, System.currentTimeMillis)
      val clock3_2 = clock2_2.increment(2, System.currentTimeMillis)

      clock5_1.compare(clock3_2) must be(After)
    }
  }

  "A Versioned" must {
    class TestVersioned(val version: VectorClock = VectorClock()) extends Versioned {
      def increment(v: Int, time: Long) = new TestVersioned(version.increment(v, time))
    }

    "have zero versions when created" in {
      val versioned = new TestVersioned()
      versioned.version.versions must be(Vector())
    }

    "happen before an identical versioned with a single additional event" in {
      val versioned1_1 = new TestVersioned()
      val versioned2_1 = versioned1_1.increment(1, System.currentTimeMillis)
      val versioned3_1 = versioned2_1.increment(2, System.currentTimeMillis)
      val versioned4_1 = versioned3_1.increment(1, System.currentTimeMillis)

      val versioned1_2 = new TestVersioned()
      val versioned2_2 = versioned1_2.increment(1, System.currentTimeMillis)
      val versioned3_2 = versioned2_2.increment(2, System.currentTimeMillis)
      val versioned4_2 = versioned3_2.increment(1, System.currentTimeMillis)
      val versioned5_2 = versioned4_2.increment(3, System.currentTimeMillis)

      Versioned.latestVersionOf[TestVersioned](versioned4_1, versioned5_2) must be(versioned5_2)
    }

    "Two versioneds with different events should be concurrent: 1" in {
      var versioned1_1 = new TestVersioned()
      val versioned2_1 = versioned1_1.increment(1, System.currentTimeMillis)

      val versioned1_2 = new TestVersioned()
      val versioned2_2 = versioned1_2.increment(2, System.currentTimeMillis)

      Versioned.latestVersionOf[TestVersioned](versioned2_1, versioned2_2) must be(versioned2_1)
    }

    "Two versioneds with different events should be concurrent: 2" in {
      val versioned1_3 = new TestVersioned()
      val versioned2_3 = versioned1_3.increment(1, System.currentTimeMillis)
      val versioned3_3 = versioned2_3.increment(2, System.currentTimeMillis)
      val versioned4_3 = versioned3_3.increment(1, System.currentTimeMillis)

      val versioned1_4 = new TestVersioned()
      val versioned2_4 = versioned1_4.increment(1, System.currentTimeMillis)
      val versioned3_4 = versioned2_4.increment(1, System.currentTimeMillis)
      val versioned4_4 = versioned3_4.increment(3, System.currentTimeMillis)

      Versioned.latestVersionOf[TestVersioned](versioned4_3, versioned4_4) must be(versioned4_3)
    }

    "be earlier than another versioned if it has an older version" in {
      val versioned1_1 = new TestVersioned()
      val versioned2_1 = versioned1_1.increment(2, System.currentTimeMillis)
      val versioned3_1 = versioned2_1.increment(2, System.currentTimeMillis)

      val versioned1_2 = new TestVersioned()
      val versioned2_2 = versioned1_2.increment(1, System.currentTimeMillis)
      val versioned3_2 = versioned2_2.increment(2, System.currentTimeMillis)
      val versioned4_2 = versioned3_2.increment(2, System.currentTimeMillis)
      val versioned5_2 = versioned4_2.increment(3, System.currentTimeMillis)

      Versioned.latestVersionOf[TestVersioned](versioned3_1, versioned5_2) must be(versioned5_2)
    }

    "be later than another versioned if it has an newer version" in {
      val versioned1_1 = new TestVersioned()
      val versioned2_1 = versioned1_1.increment(1, System.currentTimeMillis)
      val versioned3_1 = versioned2_1.increment(2, System.currentTimeMillis)
      val versioned4_1 = versioned3_1.increment(2, System.currentTimeMillis)
      val versioned5_1 = versioned4_1.increment(3, System.currentTimeMillis)

      val versioned1_2 = new TestVersioned()
      val versioned2_2 = versioned1_2.increment(2, System.currentTimeMillis)
      val versioned3_2 = versioned2_2.increment(2, System.currentTimeMillis)

      Versioned.latestVersionOf[TestVersioned](versioned5_1, versioned3_2) must be(versioned5_1)
    }
  }
}
