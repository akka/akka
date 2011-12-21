/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import akka.actor.dispatch.ActorModelSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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

  val dispatcherCount = new AtomicInteger()

  override def registerInterceptedDispatcher(): MessageDispatcherInterceptor = {
    // use new id for each invocation, since the MessageDispatcherInterceptor holds state
    val dispatcherId = "test-calling-thread" + dispatcherCount.incrementAndGet()
    val dispatcherConfigurator = new MessageDispatcherConfigurator(system.dispatcherFactory.defaultDispatcherConfig, system.dispatcherFactory.prerequisites) {
      val instance = new CallingThreadDispatcher(prerequisites) with MessageDispatcherInterceptor {
        override def id: String = dispatcherId
      }
      override def dispatcher(): MessageDispatcher = instance
    }
    system.dispatcherFactory.register(dispatcherId, dispatcherConfigurator)
    system.dispatcherFactory.lookup(dispatcherId).asInstanceOf[MessageDispatcherInterceptor]
  }
  override def dispatcherType = "Calling Thread Dispatcher"

}
