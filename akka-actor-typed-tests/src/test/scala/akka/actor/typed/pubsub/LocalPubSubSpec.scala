/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.pubsub

import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.concurrent.duration._

class LocalPubSubSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  "A pub-sub topic running locally" must {

    "publish to all local subscriber actors of a topic" in {
      val fruitTopic =
        LoggingTestKit.debug("Topic list updated").expect {
          testKit.spawn(Topic[String]("fruit"))
        }

      try {
        val probe1 = testKit.createTestProbe[String]
        val probe2 = testKit.createTestProbe[String]
        val probe3 = testKit.createTestProbe[String]

        LoggingTestKit.debug("Topic list updated").expect {
          fruitTopic ! Topic.Subscribe(probe1.ref)
          fruitTopic ! Topic.Subscribe(probe2.ref)
          fruitTopic ! Topic.Subscribe(probe3.ref)
        }

        fruitTopic ! Topic.Publish("banana")
        probe1.expectMessage("banana")
        probe2.expectMessage("banana")
        probe3.expectMessage("banana")

      } finally {
        testKit.stop(fruitTopic)
      }
    }

    "publish to all subscriber actors across several instances of the same topic" in {
      val fruitTopic1 =
        LoggingTestKit.debug("Topic list updated").expect {
          testKit.spawn(Topic[String]("fruit"))
        }
      val fruitTopic2 =
        LoggingTestKit.debug("Topic list updated").expect {
          testKit.spawn(Topic[String]("fruit"))
        }

      try {
        val probe1 = testKit.createTestProbe[String]
        val probe2 = testKit.createTestProbe[String]
        val probe3 = testKit.createTestProbe[String]

        LoggingTestKit
          .debug("Topic list updated")
          // both topic instances should have seen the updated list
          .withOccurrences(2)
          .expect {
            fruitTopic2 ! Topic.Subscribe(probe1.ref)
            fruitTopic2 ! Topic.Subscribe(probe2.ref)
            fruitTopic2 ! Topic.Subscribe(probe3.ref)
          }

        fruitTopic1 ! Topic.Publish("banana")
        probe1.expectMessage("banana")
        probe2.expectMessage("banana")
        probe3.expectMessage("banana")

      } finally {
        testKit.stop(fruitTopic1)
        testKit.stop(fruitTopic2)
      }
    }

    "send to a single actor across several instances of the same topic" in {
      val animalTopic1 =
        LoggingTestKit.debug("Topic list updated").expect {
          testKit.spawn(Topic[String]("animal"))
        }
      val animalTopic2 =
        LoggingTestKit.debug("Topic list updated").expect {
          testKit.spawn(Topic[String]("animal"))
        }

      try {
        val probe1 = testKit.createTestProbe[String]
        val probe2 = testKit.createTestProbe[String]
        val probe3 = testKit.createTestProbe[String]

        LoggingTestKit
          .debug("Topic list updated")
          // both topic instances should have seen the updated list
          // once both topics registers (subscribers on both)
          .withOccurrences(4)
          .expect {
            animalTopic1 ! Topic.Subscribe(probe1.ref)
            animalTopic2 ! Topic.Subscribe(probe2.ref)
            animalTopic2 ! Topic.Subscribe(probe3.ref)
          }

        animalTopic1 ! Topic.Send("dog", preferLocal = false)
        implicit val ec = testKit.system.executionContext
        val turnIntoOption: Try[String] => Try[Option[String]] = {
          case Success(string) => Success(Some(string))
          case Failure(_)      => Success(None: Option[String])
        }
        val allThreeResults = Future
          .sequence(Seq(Future {
            probe1.expectMessage(500.millis, "dog")
          }, Future {
            probe2.expectMessage(500.millis, "dog")
          }, Future {
            probe3.expectMessage(500.millis, "dog")
          }).map(_.transform(turnIntoOption)))
          .futureValue
          .flatten
        allThreeResults should ===(Seq("dog")) // only one should receive
      } finally {
        testKit.stop(animalTopic1)
        testKit.stop(animalTopic2)
      }
    }

  }
}
