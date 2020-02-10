/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.testkit.AkkaSpec
import akka.util.JavaVersion
import org.scalatest.matchers.should.Matchers

class RemotingFlightRecorderSpec extends AkkaSpec with Matchers {

  "The RemotingFlightRecorder" must {

    "use the no-op recorder by default when running on JDK 8" in {
      val extension = RemotingFlightRecorder(system)
      if (JavaVersion.majorVersion < 11)
        extension should ===(NoOpRemotingFlightRecorder)
    }
  }

}
