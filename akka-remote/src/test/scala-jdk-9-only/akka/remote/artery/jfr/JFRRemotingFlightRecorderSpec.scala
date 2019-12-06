/*
 * Copyright (C) 2009-2019 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery.jfr

import akka.remote.artery.RemotingFlightRecorder
import akka.testkit.AkkaSpec

class JFRRemotingFlightRecorderSpec extends AkkaSpec {

  "The RemotingFlightRecorder" must {

    "use the JFR one on Java 11" in {
      val extension = RemotingFlightRecorder(system)
      extension should be a[JFRRemotingFlightRecorder]
    }
  }

}
