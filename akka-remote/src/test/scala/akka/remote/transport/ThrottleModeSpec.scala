package akka.remote.transport

import akka.testkit.AkkaSpec
import akka.remote.transport.ThrottlerTransportAdapter.{ TokenBucket, Unthrottled }
import java.util.concurrent.TimeUnit

class ThrottleModeSpec extends AkkaSpec {

  "ThrottleMode" must {

    "allow consumption of infinite amount of tokens when untrhottled" in {
      val bucket = Unthrottled
      bucket.tryConsumeTokens(0, 100) must be((Unthrottled, true))
      bucket.tryConsumeTokens(100000, 1000) must be((Unthrottled, true))
      bucket.tryConsumeTokens(1000000, 10000) must be((Unthrottled, true))
    }

    "in tokenbucket mode allow consuming tokens up to capacity" in {
      val bucket = TokenBucket(capacity = 100, tokensPerSecond = 100, lastSend = 0L, availableTokens = 100)
      val (bucket1, success1) = bucket.tryConsumeTokens(timeOfSend = 0L, 10)
      bucket1 must be(TokenBucket(100, 100, 0, 90))
      success1 must be(true)

      val (bucket2, success2) = bucket1.tryConsumeTokens(timeOfSend = 0L, 40)
      bucket2 must be(TokenBucket(100, 100, 0, 50))
      success2 must be(true)

      val (bucket3, success3) = bucket2.tryConsumeTokens(timeOfSend = 0L, 50)
      bucket3 must be(TokenBucket(100, 100, 0, 0))
      success3 must be(true)

      val (bucket4, success4) = bucket3.tryConsumeTokens(timeOfSend = 0, 1)
      bucket4 must be(TokenBucket(100, 100, 0, 0))
      success4 must be(false)
    }

    "accurately replenish tokens" in {
      val halfSecond: Long = TimeUnit.MILLISECONDS.toNanos(500)
      val bucket = TokenBucket(capacity = 100, tokensPerSecond = 100, lastSend = 0L, availableTokens = 0)
      val (bucket1, success1) = bucket.tryConsumeTokens(timeOfSend = 0L, 0)
      bucket1 must be(TokenBucket(100, 100, 0, 0))
      success1 must be(true)

      val (bucket2, success2) = bucket1.tryConsumeTokens(timeOfSend = halfSecond, 0)
      bucket2 must be(TokenBucket(100, 100, halfSecond, 50))
      success2 must be(true)

      val (bucket3, success3) = bucket2.tryConsumeTokens(timeOfSend = 2 * halfSecond, 0)
      bucket3 must be(TokenBucket(100, 100, 2 * halfSecond, 100))
      success3 must be(true)

      val (bucket4, success4) = bucket3.tryConsumeTokens(timeOfSend = 3 * halfSecond, 0)
      bucket4 must be(TokenBucket(100, 100, 3 * halfSecond, 100))
      success4 must be(true)
    }

    "accurately interleave replenish and consume" in {
      val halfSecond: Long = TimeUnit.MILLISECONDS.toNanos(500)
      val bucket = TokenBucket(capacity = 100, tokensPerSecond = 100, lastSend = 0L, availableTokens = 20)
      val (bucket1, success1) = bucket.tryConsumeTokens(timeOfSend = 0L, 10)
      bucket1 must be(TokenBucket(100, 100, 0, 10))
      success1 must be(true)

      val (bucket2, success2) = bucket1.tryConsumeTokens(timeOfSend = halfSecond, 60)
      bucket2 must be(TokenBucket(100, 100, halfSecond, 0))
      success2 must be(true)

      val (bucket3, success3) = bucket2.tryConsumeTokens(timeOfSend = 2 * halfSecond, 40)
      bucket3 must be(TokenBucket(100, 100, 2 * halfSecond, 10))
      success3 must be(true)

      val (bucket4, success4) = bucket3.tryConsumeTokens(timeOfSend = 3 * halfSecond, 70)
      bucket4 must be(TokenBucket(100, 100, 2 * halfSecond, 10))
      success4 must be(false)
    }

    "allow oversized packets through by loaning" in {
      val halfSecond: Long = TimeUnit.MILLISECONDS.toNanos(500)
      val bucket = TokenBucket(capacity = 100, tokensPerSecond = 100, lastSend = 0L, availableTokens = 20)
      val (bucket1, success1) = bucket.tryConsumeTokens(timeOfSend = 0L, 30)
      bucket1 must be(TokenBucket(100, 100, 0, 20))
      success1 must be(false)

      val (bucket2, success2) = bucket1.tryConsumeTokens(timeOfSend = halfSecond, 110)
      bucket2 must be(TokenBucket(100, 100, halfSecond, -40))
      success2 must be(true)

      val (bucket3, success3) = bucket2.tryConsumeTokens(timeOfSend = 2 * halfSecond, 20)
      bucket3 must be(TokenBucket(100, 100, halfSecond, -40))
      success3 must be(false)

      val (bucket4, success4) = bucket3.tryConsumeTokens(timeOfSend = 3 * halfSecond, 20)
      bucket4 must be(TokenBucket(100, 100, 3 * halfSecond, 40))
      success4 must be(true)
    }

  }

}
