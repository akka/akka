/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.scaladsl

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.annotation.ApiMayChange
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

/**
 * Sources and sinks to integrate [[akka.actor.typed.pubsub.Topic]] with streams allowing for local or distributed
 * publishing and subscribing of elements through a stream.
 */
object PubSub {

  /**
   * Create a source will subscribe to a topic and stream messages published to the topic. Can be materialized
   * multiple times, each materialized stream will contain messages published after it was started.
   *
   * Topics will be looked up or created in the actor system wide topic registry [[akka.actor.typed.pubsub.PubSub]]
   *
   * Note that it is not possible to propagate the backpressure from the running stream to the pub sub topic,
   * if the stream is backpressuring published messages are buffered up to a limit and if the limit is hit
   * the configurable `OverflowStrategy` decides what happens. It is not possible to use the `Backpressure`
   * strategy.
   *
   * @param topicName The name of the topic
   * @param bufferSize The maximum number of messages to buffer if the stream applies backpressure
   * @param overflowStrategy Strategy to use once the buffer is full.
   * @tparam T The type of the published messages
   */
  @ApiMayChange
  def source[T](topicName: String, bufferSize: Int, overflowStrategy: OverflowStrategy)(
      implicit classTag: ClassTag[T]): Source[T, NotUsed] =
    Source
      .fromMaterializer { (materializer, _) =>
        val topicActor = akka.actor.typed.pubsub.PubSub(materializer.system.toTyped).topic[T](topicName)(classTag)
        source(topicActor, bufferSize, overflowStrategy)
      }
      .mapMaterializedValue(_ => NotUsed)

  /**
   * Create a source will subscribe to a topic and stream messages published to the topic. Can be materialized
   * multiple times, each materialized stream will contain messages published after it was started.
   *
   * Topics will be looked up or created in the actor system wide topic registry [[akka.actor.typed.pubsub.PubSub]]
   *
   * Note that it is not possible to propagate the backpressure from the running stream to the pub sub topic,
   * if the stream is backpressuring published messages are buffered up to a limit and if the limit is hit
   * the configurable `OverflowStrategy` decides what happens. It is not possible to use the `Backpressure`
   * strategy.
   *
   * @param topicName The name of the topic
   * @param ttl A ttl for the topic, once this stream has cancelled and no other subscribers or publish
   *            for this long it will shutdown.
   * @param bufferSize The maximum number of messages to buffer if the stream applies backpressure
   * @param overflowStrategy Strategy to use once the buffer is full.
   * @tparam T The type of the published messages
   */
  @ApiMayChange
  def source[T](topicName: String, ttl: FiniteDuration, bufferSize: Int, overflowStrategy: OverflowStrategy)(
      implicit classTag: ClassTag[T]): Source[T, NotUsed] =
    Source
      .fromMaterializer { (materializer, _) =>
        val topicActor = akka.actor.typed.pubsub.PubSub(materializer.system.toTyped).topic[T](topicName, ttl)(classTag)
        source(topicActor, bufferSize, overflowStrategy)
      }
      .mapMaterializedValue(_ => NotUsed)

  /**
   * Create a source that will subscribe to a topic and stream messages published to the topic. Can be materialized
   * multiple times, each materialized stream will contain messages published after it was started.
   *
   * Note that it is not possible to propagate the backpressure from the running stream to the pub sub topic,
   * if the stream is backpressuring published messages are buffered up to a limit and if the limit is hit
   * the configurable `OverflowStrategy` decides what happens. It is not possible to use the `Backpressure`
   * strategy.
   *
   * @param topicActor The actor ref for an `akka.actor.typed.pubsub.Topic` actor representing a specific topic.
   * @param bufferSize The maximum number of messages to buffer if the stream applies backpressure
   * @param overflowStrategy Strategy to use once the buffer is full.
   * @tparam T The type of the published messages
   */
  @ApiMayChange
  def source[T](
      topicActor: ActorRef[Topic.Command[T]],
      bufferSize: Int,
      overflowStrategy: OverflowStrategy): Source[T, NotUsed] =
    ActorSource
      .actorRef[T](PartialFunction.empty, PartialFunction.empty, bufferSize, overflowStrategy)
      .watch(topicActor.toClassic)
      .mapMaterializedValue { ref =>
        topicActor ! Topic.Subscribe(ref)
        NotUsed
      }

  /**
   * Create a sink that will publish each message to the given topic. Note that there is no backpressure
   * from the topic, so care must be taken to not publish messages at a higher rate than that can be handled
   * by subscribers. If the topic does not have any subscribers when a message is published or the topic actor is stopped,
   * the message is sent to dead letters.
   *
   * @param topicActor The actor ref for an `akka.actor.typed.pubsub.Topic` actor representing a specific topic.
   * @tparam T the type of the messages that can be published
   */
  @ApiMayChange
  def sink[T](topicName: String)(implicit classTag: ClassTag[T]): Sink[T, NotUsed] =
    Sink
      .fromMaterializer { (materializer, _) =>
        val topicActor = akka.actor.typed.pubsub.PubSub(materializer.system.toTyped).topic[T](topicName)(classTag)
        sink(topicActor)
      }
      .mapMaterializedValue(_ => NotUsed)

  /**
   * Create a sink that will publish each message to the given topic. Note that there is no backpressure
   * from the topic, so care must be taken to not publish messages at a higher rate than that can be handled
   * by subscribers. If the topic does not have any subscribers when a message is published or the topic actor is stopped,
   * the message is sent to dead letters.
   *
   * @param topicActor The actor ref for an `akka.actor.typed.pubsub.Topic` actor representing a specific topic.
   * @param ttl A ttl for the topic, after no publish or subscribers for this long it will shutdown, and terminate the stream
   * @tparam T the type of the messages that can be published
   */
  @ApiMayChange
  def sink[T](topicName: String, ttl: FiniteDuration)(implicit classTag: ClassTag[T]): Sink[T, NotUsed] =
    Sink
      .fromMaterializer { (materializer, _) =>
        val topicActor = akka.actor.typed.pubsub.PubSub(materializer.system.toTyped).topic[T](topicName, ttl)(classTag)
        sink(topicActor)
      }
      .mapMaterializedValue(_ => NotUsed)

  /**
   * Create a sink that will publish each message to the given topic. Note that there is no backpressure
   * from the topic, so care must be taken to not publish messages at a higher rate than that can be handled
   * by subscribers. If the topic does not have any subscribers when a message is published or the topic actor is stopped,
   * the message is sent to dead letters.
   *
   * @param topicActor The actor ref for an `akka.actor.typed.pubsub.Topic` actor representing a specific topic.
   * @tparam T the type of the messages that can be published
   */
  @ApiMayChange
  def sink[T](topicActor: ActorRef[Topic.Command[T]]): Sink[T, NotUsed] =
    Flow[T]
      .watch(topicActor.toClassic)
      .to(Sink
        .foreach[T] { message =>
          topicActor ! Topic.Publish(message)
        }
        .mapMaterializedValue(_ => NotUsed))
}
