/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.util.JavaVersion
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpec

class VirtualThreadDispatcherSpec extends AnyWordSpec {

  "The virtual thread support" should {

    "run tasks on virtual threads" in {
      if (JavaVersion.majorVersion < 21) {
        // loom not available yet here
        pending
      } else {
        val system = ActorSystem(
          classOf[VirtualThreadDispatcherSpec].getSimpleName,
          ConfigFactory.parseString("""
              my-vt-dispatcher {
                type = "Dispatcher"
                executor = virtual-thread-executor
              }
            """).withFallback(ConfigFactory.load()))

        try {
          val vtDispatcher = system.dispatchers.lookup("my-vt-dispatcher")
          val threadIsVirtualProbe = TestProbe()(system)
          vtDispatcher.execute(() => {
            val thread = Thread.currentThread()
            // can't use methods directly or test won't compile on jdk < 21
            val isVirtualMethod = thread.getClass.getMethod("isVirtual")
            val result = isVirtualMethod.invoke(thread).asInstanceOf[Boolean]
            threadIsVirtualProbe.ref ! result
          })
          threadIsVirtualProbe.expectMsg(true)
        } finally {
          if (system != null) TestKit.shutdownActorSystem(system)
        }

      }
    }
  }

}
