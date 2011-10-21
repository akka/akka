package akka.remote

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import java.net.InetSocketAddress

class VectorClockSpec extends WordSpec with MustMatchers {
  import VectorClock._

  "An VectorClock" must {

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

    "A clock should not happen before an identical clock" in {
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

    "A clock should happen before an identical clock with a single additional event" in {
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
}