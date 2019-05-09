/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit.scaladsl

import akka.actor.ActorSystem
import akka.stream.Attributes.none
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit._
import akka.stream.testkit.StreamTestKit.ProbeSink

/**
 * Factory methods for test sinks.
 */
object TestSink {

  /**
   * A Sink that materialized to a [[akka.stream.testkit.TestSubscriber.Probe]].
   */
  def probe[T](implicit system: ActorSystem): Sink[T, Probe[T]] =
    Sink.fromGraph[T, TestSubscriber.Probe[T]](new ProbeSink(none, SinkShape(Inlet("ProbeSink.in"))))

}
