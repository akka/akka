/*
 * Copyright (C) 2013-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import akka.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ImplicitSenderSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with TestKitBase with ImplicitSender {

  implicit lazy val system = ActorSystem("AkkaCustomSpec")

  override def afterAll = system.terminate

  "An ImplicitSender" should {
    "have testActor as its self" in {
      self should ===(testActor)
    }
  }
}
