package akka.stream.scaladsl

import akka.stream.testkit.AkkaSpec

/**
  * Created by lolski on 11/25/15.
  */
class FlowLimitSpec extends AkkaSpec {
  // Source(size = n).limit(n) == ???
  "???" must {

  }

  // Source(size = m).limit(n) where m < n == ???
  // Source(size = m).limit(n) where m > n == ???

  // Source(size = n).limit(n) where one of the element throws an exception
}