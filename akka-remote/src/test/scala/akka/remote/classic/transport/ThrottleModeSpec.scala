/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic.transport

import java.util.concurrent.TimeUnit

import akka.remote.transport.ThrottlerTransportAdapter.{ TokenBucket, Unthrottled }
import akka.testkit.AkkaSpec

class ThrottleModeSpec extends AkkaSpec {

  val halfSecond: Long = TimeUnit.MILLISECONDS.toNanos(500)

  "ThrottleMode" must {

    "allow consumption of infinite amount of tokens when untrhottled" in {
      val bucket = Unthrottled
      bucket.tryConsumeTokens(0, 100) should ===((Unthrottled, true))
      bucket.tryConsumeTokens(100000, 1000) should ===((Unthrottled, true))
      bucket.tryConsumeTokens(1000000, 10000) should ===((Unthrottled, true))
    }

    "in tokenbucket mode allow consuming tokens up to capacity" in {
      val bucket = TokenBucket(capacity = 100, tokensPerSecond = 100, nanoTimeOfLastSend = 0L, availableTokens = 100)
      val (bucket1, success1) = bucket.tryConsumeTokens(nanoTimeOfSend = 0L, 10)
      bucket1 should ===(TokenBucket(100, 100, 0, 90))
      success1 should ===(true)

      val (bucket2, success2) = bucket1.tryConsumeTokens(nanoTimeOfSend = 0L, 40)
      bucket2 should ===(TokenBucket(100, 100, 0, 50))
      success2 should ===(true)

      val (bucket3, success3) = bucket2.tryConsumeTokens(nanoTimeOfSend = 0L, 50)
      bucket3 should ===(TokenBucket(100, 100, 0, 0))
      success3 should ===(true)

      val (bucket4, success4) = bucket3.tryConsumeTokens(nanoTimeOfSend = 0, 1)
      bucket4 should ===(TokenBucket(100, 100, 0, 0))
      success4 should ===(false)
    }

    "accurately replenish tokens" in {
      val bucket = TokenBucket(capacity = 100, tokensPerSecond = 100, nanoTimeOfLastSend = 0L, availableTokens = 0)
      val (bucket1, success1) = bucket.tryConsumeTokens(nanoTimeOfSend = 0L, 0)
      bucket1 should ===(TokenBucket(100, 100, 0, 0))
      success1 should ===(true)

      val (bucket2, success2) = bucket1.tryConsumeTokens(nanoTimeOfSend = halfSecond, 0)
      bucket2 should ===(TokenBucket(100, 100, halfSecond, 50))
      success2 should ===(true)

      val (bucket3, success3) = bucket2.tryConsumeTokens(nanoTimeOfSend = 2 * halfSecond, 0)
      bucket3 should ===(TokenBucket(100, 100, 2 * halfSecond, 100))
      success3 should ===(true)

      val (bucket4, success4) = bucket3.tryConsumeTokens(nanoTimeOfSend = 3 * halfSecond, 0)
      bucket4 should ===(TokenBucket(100, 100, 3 * halfSecond, 100))
      success4 should ===(true)
    }

    "accurately interleave replenish and consume" in {
      val bucket = TokenBucket(capacity = 100, tokensPerSecond = 100, nanoTimeOfLastSend = 0L, availableTokens = 20)
      val (bucket1, success1) = bucket.tryConsumeTokens(nanoTimeOfSend = 0L, 10)
      bucket1 should ===(TokenBucket(100, 100, 0, 10))
      success1 should ===(true)

      val (bucket2, success2) = bucket1.tryConsumeTokens(nanoTimeOfSend = halfSecond, 60)
      bucket2 should ===(TokenBucket(100, 100, halfSecond, 0))
      success2 should ===(true)

      val (bucket3, success3) = bucket2.tryConsumeTokens(nanoTimeOfSend = 2 * halfSecond, 40)
      bucket3 should ===(TokenBucket(100, 100, 2 * halfSecond, 10))
      success3 should ===(true)

      val (bucket4, success4) = bucket3.tryConsumeTokens(nanoTimeOfSend = 3 * halfSecond, 70)
      bucket4 should ===(TokenBucket(100, 100, 2 * halfSecond, 10))
      success4 should ===(false)
    }

    "allow oversized packets through by loaning" in {
      val bucket = TokenBucket(capacity = 100, tokensPerSecond = 100, nanoTimeOfLastSend = 0L, availableTokens = 20)
      val (bucket1, success1) = bucket.tryConsumeTokens(nanoTimeOfSend = 0L, 30)
      bucket1 should ===(TokenBucket(100, 100, 0, 20))
      success1 should ===(false)

      val (bucket2, success2) = bucket1.tryConsumeTokens(nanoTimeOfSend = halfSecond, 110)
      bucket2 should ===(TokenBucket(100, 100, halfSecond, -40))
      success2 should ===(true)

      val (bucket3, success3) = bucket2.tryConsumeTokens(nanoTimeOfSend = 2 * halfSecond, 20)
      bucket3 should ===(TokenBucket(100, 100, halfSecond, -40))
      success3 should ===(false)

      val (bucket4, success4) = bucket3.tryConsumeTokens(nanoTimeOfSend = 3 * halfSecond, 20)
      bucket4 should ===(TokenBucket(100, 100, 3 * halfSecond, 40))
      success4 should ===(true)
    }

  }

}
