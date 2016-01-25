/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream.scaladsl

object TestConfig {
  val numberOfTestsToRun = System.getProperty("akka.stream.test.numberOfRandomizedTests", "10").toInt
  val RandomTestRange = 1 to numberOfTestsToRun
}
