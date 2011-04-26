/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, BlockingQueue}
import akka.config. {RemoteAddress, Config, TypedActorConfigurator}


object RemoteTypedActorLog {
  val messageLog: BlockingQueue[String] = new LinkedBlockingQueue[String]
  val oneWayLog = new LinkedBlockingQueue[String]

  def clearMessageLogs {
    messageLog.clear
    oneWayLog.clear
  }
}

class RemoteTypedActorSpec extends AkkaRemoteTest {

  "RemoteModule Typed Actor " should {
    "have unit tests" in {
    }
  }
}
