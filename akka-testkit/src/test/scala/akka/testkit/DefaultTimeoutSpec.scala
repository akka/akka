/**
 * Copyright (C) 2013-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit

import org.scalatest.{ WordSpec, BeforeAndAfterAll }
import org.scalatest.Matchers
import akka.actor.ActorSystem

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DefaultTimeoutSpec
  extends WordSpec with Matchers with BeforeAndAfterAll with TestKitBase with DefaultTimeout {

  implicit lazy val system = ActorSystem("AkkaCustomSpec")

  override def afterAll = system.terminate

  "A spec with DefaultTimeout" should {
    "use timeout from settings" in {
      timeout should ===(testKitSettings.DefaultTimeout)
    }
  }
}