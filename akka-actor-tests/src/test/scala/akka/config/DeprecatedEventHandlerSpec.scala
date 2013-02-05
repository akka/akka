/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import scala.concurrent.duration._
import akka.testkit.AkkaSpec
import akka.actor.Actor
import akka.event.Logging.InitializeLogger
import akka.event.Logging.LogEvent
import akka.event.Logging.LoggerInitialized
import akka.event.Logging.Error
import akka.util.Timeout

object DeprecatedEventHandlerSpec {

  case class WrappedLogEvent(event: Any)

  class TestEventHandler extends Actor {
    def receive = {
      case init: InitializeLogger ⇒
        sender ! LoggerInitialized
      case err: Error ⇒
        context.system.eventStream.publish(WrappedLogEvent(err))
      case event: LogEvent ⇒
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DeprecatedEventHandlerSpec extends AkkaSpec("""
      akka.event-handlers = ["akka.config.DeprecatedEventHandlerSpec$TestEventHandler"]
      akka.event-handler-startup-timeout = 17s
    """) {

  import DeprecatedEventHandlerSpec._

  "Akka 2.2" must {
    "use deprected event-handler properties" in {
      system.settings.EventHandlers must be(List(classOf[TestEventHandler].getName))
      system.settings.EventHandlerStartTimeout must be(Timeout(17.seconds))

      system.eventStream.subscribe(testActor, classOf[WrappedLogEvent])

      system.log.error("test error")
      expectMsgPF(remaining) {
        case WrappedLogEvent(Error(_, _, _, "test error")) ⇒
      }

    }
  }
}
