/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq.test

import java.util.concurrent.{ Executors, TimeUnit }
import org.scalatest.{ WordSpec }
import org.scalatest.matchers.MustMatchers
import scala.util.Random

trait Specification extends WordSpec with MustMatchers {
  def waitUntil(done: ⇒ Boolean) = {
    val executor = Executors.newSingleThreadScheduledExecutor
    executor.scheduleAtFixedRate(new Runnable {
      def run { if (done) executor.shutdown }
    }, 0, 1, TimeUnit.SECONDS)
    executor.awaitTermination(waitUntilTimeoutSec, TimeUnit.SECONDS)
  }
  def waitUntilTimeoutSec = 10
  def randomPort = 1024 + new Random(System.currentTimeMillis).nextInt(4096)
}
