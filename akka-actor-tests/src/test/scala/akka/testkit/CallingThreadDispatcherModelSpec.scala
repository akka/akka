/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import akka.actor.dispatch.ActorModelSpec
import java.util.concurrent.CountDownLatch
import org.junit.{ After, Test }
import com.typesafe.config.Config
import akka.dispatch.DispatcherPrerequisites
import akka.dispatch.MessageDispatcher
import akka.dispatch.MessageDispatcherConfigurator

object CallingThreadDispatcherModelSpec {
  val config = """
    boss {
      type = PinnedDispatcher
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CallingThreadDispatcherModelSpec extends ActorModelSpec(CallingThreadDispatcherModelSpec.config) {
  import ActorModelSpec._

  var dispatcherCount = 0

  override def registerInterceptedDispatcher(): MessageDispatcherInterceptor = {
    // use new key for each invocation, since the MessageDispatcherInterceptor holds state
    dispatcherCount += 1
    val confKey = "test-calling-thread" + dispatcherCount
    val dispatcherConfigurator = new MessageDispatcherConfigurator(system.dispatcherFactory.defaultDispatcherConfig, system.dispatcherFactory.prerequisites) {
      val instance = new CallingThreadDispatcher(prerequisites) with MessageDispatcherInterceptor {
        override def key: String = confKey
      }
      override def dispatcher(): MessageDispatcher = instance
    }
    system.dispatcherFactory.register(confKey, dispatcherConfigurator)
    system.dispatcherFactory.lookup(confKey).asInstanceOf[MessageDispatcherInterceptor]
  }
  override def dispatcherType = "Calling Thread Dispatcher"

}
