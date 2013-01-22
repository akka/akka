/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.testkit.{ TestProbe, AkkaSpec }
import Tcp._
import TestUtils._

class CapacityLimitSpec extends AkkaSpec("akka.loglevel = ERROR\nakka.io.tcp.max-channels = 4")
  with IntegrationSpecSupport {

  "The TCP transport implementation" should {

    "reply with CommandFailed to a Bind or Connect command if max-channels capacity has been reached" in new TestSetup {
      establishNewClientConnection()

      // we now have three channels registered: a listener, a server connection and a client connection
      // so register one more channel
      val bindCommander = TestProbe()
      bindCommander.send(IO(Tcp), Bind(bindHandler.ref, temporaryServerAddress()))
      bindCommander.expectMsg(Bound)

      // we are now at the configured max-channel capacity of 4
      val bindToFail = Bind(bindHandler.ref, temporaryServerAddress())
      bindCommander.send(IO(Tcp), bindToFail)
      bindCommander.expectMsgType[CommandFailed].cmd must be theSameInstanceAs (bindToFail)
    }

  }

}
