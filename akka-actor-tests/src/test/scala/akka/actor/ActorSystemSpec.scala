/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import org.scalatest.junit.JUnitSuite
import com.typesafe.config.ConfigFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import akka.dispatch.Await
import akka.util.duration._
import scala.collection.JavaConverters

class JavaExtensionSpec extends JavaExtension with JUnitSuite

object TestExtension extends ExtensionId[TestExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ActorSystemImpl) = new TestExtension(s)
}

// Dont't place inside ActorSystemSpec object, since it will not be garbage collected and reference to system remains
class TestExtension(val system: ActorSystemImpl) extends Extension

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorSystemSpec extends AkkaSpec("""akka.extensions = ["akka.actor.TestExtension$"]""") {

  "An ActorSystem" must {

    "support extensions" in {
      TestExtension(system).system must be === system
      system.extension(TestExtension).system must be === system
      system.hasExtension(TestExtension) must be(true)
    }

    "run termination callbacks in order" in {
      import scala.collection.JavaConverters._

      val system2 = ActorSystem("TerminationCallbacks", AkkaSpec.testConf)
      val result = new ConcurrentLinkedQueue[Int]
      val count = 10
      val latch = TestLatch(count)

      for (i ← 1 to count) {
        system2.registerOnTermination {
          (i % 3).millis.dilated.sleep()
          result add i
          latch.countDown()
        }
      }

      system2.shutdown()
      Await.ready(latch, 5 seconds)

      val expected = (for (i ← 1 to count) yield i).reverse
      result.asScala.toSeq must be(expected)

    }

    "awaitTtermination after termination callbacks" in {
      import scala.collection.JavaConverters._

      val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
      @volatile
      var callbackWasRun = false

      system2.registerOnTermination {
        50.millis.dilated.sleep()
        callbackWasRun = true
      }

      system2.scheduler.scheduleOnce(200.millis.dilated) {
        system2.shutdown()
      }

      system2.awaitTermination()
      callbackWasRun must be(true)

    }

  }

}