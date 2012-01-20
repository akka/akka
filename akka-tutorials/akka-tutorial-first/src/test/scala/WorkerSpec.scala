/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.tutorial.first.scala

import org.junit.runner.RunWith
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import akka.testkit.TestActorRef
import akka.tutorial.first.scala.Pi.Worker
import akka.actor.ActorSystem

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class WorkerSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  implicit val system = ActorSystem()

  override def afterAll {
    system.shutdown()
  }

  "Worker" must {
    "calculate pi correctly" in {
      val testActor = TestActorRef[Worker]
      val actor = testActor.underlyingActor
      actor.calculatePiFor(0, 0) must equal(0.0)
      actor.calculatePiFor(1, 1) must be(-1.3333333333333333 plusOrMinus 0.0000000001)
    }
  }
}
