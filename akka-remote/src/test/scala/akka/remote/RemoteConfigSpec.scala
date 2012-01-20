/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit.AkkaSpec
import akka.util.duration._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteConfigSpec extends AkkaSpec("") {

  "RemoteExtension" must {
    "be able to parse remote and cluster config elements" in {

      val settings = new RemoteSettings(system.settings.config, "")
      import settings._

      RemoteTransport must be("akka.remote.netty.NettyRemoteTransport")
      UntrustedMode must be(false)
      RemoteSystemDaemonAckTimeout must be(30 seconds)

      FailureDetectorThreshold must be(8)
      FailureDetectorMaxSampleSize must be(1000)

      InitialDelayForGossip must be(5 seconds)
      GossipFrequency must be(1 second)
      SeedNodes must be(Set())

    }
  }
}
