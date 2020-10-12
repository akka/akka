/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.pubsub

import scala.concurrent.duration._

import org.scalatest.wordspec.AnyWordSpecLike

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.internal.pubsub.TopicImpl

class LocalPubSubSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  "A pub-sub topic running locally" must {

    "publish to all local subscriber actors of a topic" in {
      val fruitTopic =
        LoggingTestKit.debug("Topic list updated").expect {
          testKit.spawn(Topic[String]("fruit"))
        }

      try {
        val probe1 = testKit.createTestProbe[String]()
        val probe2 = testKit.createTestProbe[String]()
        val probe3 = testKit.createTestProbe[String]()

        fruitTopic ! Topic.Subscribe(probe1.ref)
        fruitTopic ! Topic.Subscribe(probe2.ref)
        fruitTopic ! Topic.Subscribe(probe3.ref)

        // the subscriber registration of all 3 completed
        val statsProbe = testKit.createTestProbe[TopicImpl.TopicStats]()
        statsProbe.awaitAssert {
          fruitTopic ! TopicImpl.GetTopicStats(statsProbe.ref)
          statsProbe.receiveMessage().localSubscriberCount should ===(3)
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
      val (fruitTopic1, fruitTopic2) = LoggingTestKit.debug("Topic list updated").withOccurrences(2).expect {
        (testKit.spawn(Topic[String]("fruit")), testKit.spawn(Topic[String]("fruit")))
      }

      try {
        val probe1 = testKit.createTestProbe[String]()
        val probe2 = testKit.createTestProbe[String]()
        val probe3 = testKit.createTestProbe[String]()

        LoggingTestKit
          .debug("Topic list updated")
          // both topic instances should have seen the updated list
          .withOccurrences(2)
          .expect {
            fruitTopic2 ! Topic.Subscribe(probe1.ref)
            fruitTopic2 ! Topic.Subscribe(probe2.ref)
            fruitTopic2 ! Topic.Subscribe(probe3.ref)
          }

        // the subscriber registration of all 3 completed
        val statsProbe = testKit.createTestProbe[Any]()
        statsProbe.awaitAssert {
          fruitTopic2 ! TopicImpl.GetTopicStats(statsProbe.ref)
          statsProbe.expectMessageType[TopicImpl.TopicStats].localSubscriberCount should ===(3)
        }
        // and the other topic knows about it
        statsProbe.awaitAssert {
          fruitTopic1 ! TopicImpl.GetTopicStats(statsProbe.ref)
          statsProbe.expectMessageType[TopicImpl.TopicStats].topicInstanceCount should ===(1)
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

    "doesn't publish across topics unsubscribe" in {
      val fruitTopic =
        testKit.spawn(Topic[String]("fruit"))

      val veggieTopic =
        testKit.spawn(Topic[String]("veggies"))

      try {
        val probe1 = testKit.createTestProbe[String]()

        fruitTopic ! Topic.Subscribe(probe1.ref)
        // the subscriber registration of all 3 completed
        val statsProbe = testKit.createTestProbe[Any]()
        statsProbe.awaitAssert {
          fruitTopic ! TopicImpl.GetTopicStats(statsProbe.ref)
          statsProbe.expectMessageType[TopicImpl.TopicStats].localSubscriberCount should ===(1)
        }

        // the other topic should not know about it
        statsProbe.awaitAssert {
          veggieTopic ! TopicImpl.GetTopicStats(statsProbe.ref)
          statsProbe.expectMessageType[TopicImpl.TopicStats].topicInstanceCount should ===(0)
        }

        veggieTopic ! Topic.Publish("carrot")
        probe1.expectNoMessage(200.millis)

      } finally {
        testKit.stop(fruitTopic)
      }
    }

    "doesn't publish after unsubscribe" in {
      val fruitTopic =
        LoggingTestKit.debug("Topic list updated").expect {
          testKit.spawn(Topic[String]("fruit"))
        }

      try {
        val probe1 = testKit.createTestProbe[String]()

        fruitTopic ! Topic.Subscribe(probe1.ref)
        // the subscriber registration of completed
        val statsProbe = testKit.createTestProbe[Any]()
        statsProbe.awaitAssert {
          fruitTopic ! TopicImpl.GetTopicStats(statsProbe.ref)
          statsProbe.expectMessageType[TopicImpl.TopicStats].localSubscriberCount should ===(1)
        }

        fruitTopic ! Topic.Unsubscribe(probe1.ref)
        statsProbe.awaitAssert {
          fruitTopic ! TopicImpl.GetTopicStats(statsProbe.ref)
          statsProbe.expectMessageType[TopicImpl.TopicStats].localSubscriberCount should ===(0)
        }

        fruitTopic ! Topic.Publish("banana")
        probe1.expectNoMessage(200.millis)

      } finally {
        testKit.stop(fruitTopic)
      }
    }

  }
}
