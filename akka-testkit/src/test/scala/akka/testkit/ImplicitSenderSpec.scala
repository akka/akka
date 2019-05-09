/*
 * Copyright (C) 2013-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.Matchers
import akka.actor.ActorSystem

class ImplicitSenderSpec extends WordSpec with Matchers with BeforeAndAfterAll with TestKitBase with ImplicitSender {

  implicit lazy val system = ActorSystem("AkkaCustomSpec")

  override def afterAll = system.terminate

  "An ImplicitSender" should {
    "have testActor as its self" in {
      self should ===(testActor)
    }
  }
}
