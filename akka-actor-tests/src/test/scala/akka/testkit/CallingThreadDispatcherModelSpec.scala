/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import akka.actor.dispatch.ActorModelSpec
import java.util.concurrent.CountDownLatch
import org.junit.{ After, Test }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CallingThreadDispatcherModelSpec extends ActorModelSpec {
  import ActorModelSpec._

  def newInterceptedDispatcher = new CallingThreadDispatcher(app, "test", true) with MessageDispatcherInterceptor
  def dispatcherType = "Calling Thread Dispatcher"

}
