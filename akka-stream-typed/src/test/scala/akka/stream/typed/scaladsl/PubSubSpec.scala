/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.scaladsl

import org.scalatest.wordspec.AnyWordSpecLike
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.internal.pubsub.TopicImpl
import akka.actor.typed.pubsub.Topic
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.TimingTest

import scala.concurrent.duration.DurationInt

class PubSubSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  "PubSub.source" should {

    "emit messages from a topic actor" in {
      val topic = testKit.spawn(Topic[String]("my-source-topic-1"))

      val source = PubSub.source(topic, 100, OverflowStrategy.fail)
      val sourceProbe = source.runWith(TestSink())
      sourceProbe.ensureSubscription()

      // wait until subscription has been seen
      val probe = testKit.createTestProbe[TopicImpl.TopicStats]()
      probe.awaitAssert {
        topic ! TopicImpl.GetTopicStats(probe.ref)
        probe.expectMessageType[TopicImpl.TopicStats].localSubscriberCount should ===(1)
      }

      topic ! Topic.Publish("published")
      sourceProbe.requestNext("published")
      sourceProbe.cancel()
      testKit.stop(topic)
    }

    "emit messages from a topic name" in {
      val topic = akka.actor.typed.pubsub.PubSub(system).topic[String]("my-source-topic-2")

      val source = PubSub.source[String]("my-source-topic-2", 100, OverflowStrategy.fail)
      val sourceProbe = source.runWith(TestSink())
      sourceProbe.ensureSubscription()

      // wait until subscription has been seen
      val probe = testKit.createTestProbe[TopicImpl.TopicStats]()
      probe.awaitAssert {
        topic ! TopicImpl.GetTopicStats(probe.ref)
        probe.expectMessageType[TopicImpl.TopicStats].localSubscriberCount should ===(1)
      }

      topic ! Topic.Publish("published")
      sourceProbe.requestNext("published")
      sourceProbe.cancel()
    }

    "let a topic time out after unsubscribing" taggedAs TimingTest in {
      val sub = PubSub.source[String]("my-source-topic-3", 400.millis, 100, OverflowStrategy.fail).runWith(TestSink())
      val topic = akka.actor.typed.pubsub.PubSub(system).topic[String]("my-source-topic-3", 400.millis) // same instance, same TTL
      sub.cancel() // last subscriber unsubscribed
      val probe = testKit.createTestProbe[Any]()
      probe.expectTerminated(topic)
    }

  }

  "PubSub.sink" should {
    "publish messages to a topic actor" in {
      val topic = testKit.spawn(Topic[String]("my-sink-topic-1"))

      val subscriberProbe = testKit.createTestProbe[String]()
      topic ! Topic.Subscribe(subscriberProbe.ref)

      // wait until subscription has been seen
      val probe = testKit.createTestProbe[TopicImpl.TopicStats]()
      probe.awaitAssert {
        topic ! TopicImpl.GetTopicStats(probe.ref)
        probe.expectMessageType[TopicImpl.TopicStats].localSubscriberCount should ===(1)
      }

      Source.single("published").runWith(PubSub.sink(topic))

      subscriberProbe.expectMessage("published")
      testKit.stop(topic)
    }

    "publish messages to a topic name" in {
      val topic = akka.actor.typed.pubsub.PubSub(system).topic[String]("my-sink-topic-2")

      val subscriberProbe = testKit.createTestProbe[String]()
      topic ! Topic.Subscribe(subscriberProbe.ref)

      // wait until subscription has been seen
      val probe = testKit.createTestProbe[TopicImpl.TopicStats]()
      probe.awaitAssert {
        topic ! TopicImpl.GetTopicStats(probe.ref)
        probe.expectMessageType[TopicImpl.TopicStats].localSubscriberCount should ===(1)
      }

      Source.single("published").runWith(PubSub.sink("my-sink-topic-2"))

      subscriberProbe.expectMessage("published")
    }

    "let a topic time out after ttl" taggedAs TimingTest in {
      val pub = TestSource[String]().toMat(PubSub.sink[String]("my-sink-topic-3", 400.millis))(Keep.left).run()
      pub.sendNext("one")
      val topic = akka.actor.typed.pubsub.PubSub(system).topic[String]("my-sink-topic-3") // same instance
      val probe = testKit.createTestProbe[Any]()
      probe.expectTerminated(topic)
      pub.expectCancellation()
    }
  }

}
