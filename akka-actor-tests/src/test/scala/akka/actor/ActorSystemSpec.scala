/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import org.scalatest.junit.JUnitSuite
import com.typesafe.config.ConfigFactory
import akka.dispatch.Await
import scala.util.duration._
import scala.collection.JavaConverters
import java.util.concurrent.{ TimeUnit, RejectedExecutionException, CountDownLatch, ConcurrentLinkedQueue }

class JavaExtensionSpec extends JavaExtension with JUnitSuite

object TestExtension extends ExtensionId[TestExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ExtendedActorSystem) = new TestExtension(s)
}

// Dont't place inside ActorSystemSpec object, since it will not be garbage collected and reference to system remains
class TestExtension(val system: ExtendedActorSystem) extends Extension

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
          Thread.sleep((i % 3).millis.dilated.toMillis)
          result add i
          latch.countDown()
        }
      }

      system2.shutdown()
      Await.ready(latch, 5 seconds)

      val expected = (for (i ← 1 to count) yield i).reverse
      result.asScala.toSeq must be(expected)

    }

    "awaitTermination after termination callbacks" in {
      import scala.collection.JavaConverters._

      val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
      @volatile
      var callbackWasRun = false

      system2.registerOnTermination {
        Thread.sleep(50.millis.dilated.toMillis)
        callbackWasRun = true
      }

      system2.scheduler.scheduleOnce(200.millis.dilated) { system2.shutdown() }

      system2.awaitTermination(5 seconds)
      callbackWasRun must be(true)
    }

    "throw RejectedExecutionException when shutdown" in {
      val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
      system2.shutdown()
      system2.awaitTermination(5 seconds)

      intercept[RejectedExecutionException] {
        system2.registerOnTermination { println("IF YOU SEE THIS THEN THERE'S A BUG HERE") }
      }.getMessage must be("Must be called prior to system shutdown.")
    }

  }

}