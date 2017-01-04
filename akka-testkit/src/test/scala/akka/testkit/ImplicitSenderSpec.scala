/**
 * Copyright (C) 2013-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit

import org.scalatest.{ WordSpec, BeforeAndAfterAll }
import org.scalatest.Matchers
import akka.actor.ActorSystem

class ImplicitSenderSpec
  extends WordSpec with Matchers with BeforeAndAfterAll with TestKitBase with ImplicitSender {

  implicit lazy val system = ActorSystem("AkkaCustomSpec")

  override def afterAll = system.terminate

  "An ImplicitSender" should {
    "have testActor as its self" in {
      self should ===(testActor)
    }
  }
}
