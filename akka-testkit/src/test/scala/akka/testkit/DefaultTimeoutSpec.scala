/*
 * Copyright (C) 2013-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import akka.actor.ActorSystem

class DefaultTimeoutSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with TestKitBase with DefaultTimeout {

  // TODO DOTTY
  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status = ScalatestRunTest.scalatestRun(testName, args)

  // TODO DOTTY, yes I know it's wrong
  implicit val pos: org.scalactic.source.Position = new org.scalactic.source.Position(fileName = "", filePathname = "", lineNumber = 1)

  implicit lazy val system: ActorSystem = ActorSystem("AkkaCustomSpec")

  override def afterAll() = system.terminate()

  "A spec with DefaultTimeout" should {
    "use timeout from settings" in {
      timeout should ===(testKitSettings.DefaultTimeout)
    }
  }
}
