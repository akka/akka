package se.scalablesolutions.akka.actor

import org.scalatest.Suite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers
import org.junit.{Before, After, Test}
import java.util.concurrent.{ CountDownLatch, TimeUnit }

@RunWith(classOf[JUnitRunner])
class ActorUtilTest extends junit.framework.TestCase with Suite with MustMatchers {
  import Actor._
  @Test def testSpawn = {
    val latch = new CountDownLatch(1)

    spawn {
	  latch.countDown
    }

    val done = latch.await(5,TimeUnit.SECONDS)
    done must be (true)
  }
}