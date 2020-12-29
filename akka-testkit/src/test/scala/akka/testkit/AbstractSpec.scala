/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import org.scalatest.{Args, BeforeAndAfterEach, Status}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

// we could migrate AkkaSpec to extend this
// TODO DOTTY
object ScalatestRunTest extends AnyWordSpecLike {

  val scalatestRunTest = runTest

  val scalatestRun = run

}
abstract class AbstractSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterEach {

  protected override def runTest(testName: String, args: Args): Status = ScalatestRunTest.scalatestRunTest(testName, args)

}
