/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.scaladsl

object TestConfig {
  val numberOfTestsToRun = System.getProperty("akka.stream.test.numberOfRandomizedTests", "10").toInt
  val RandomTestRange = 1 to numberOfTestsToRun
}
