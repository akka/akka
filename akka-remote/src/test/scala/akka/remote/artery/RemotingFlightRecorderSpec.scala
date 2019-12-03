/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.Matchers
import org.scalatest.WordSpec

class RemotingFlightRecorderSpec extends WordSpec with Matchers {

  "The RemotingFlightRecorder" must {

    "use the no-op recorder by default" in {
      val system = ActorSystem("RemotingFlightRecorderSpec")
      try {
        val extension = RemotingFlightRecorder(system)
        extension should ===(NoOpRemotingFlightRecorder)
      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }
  }

}
