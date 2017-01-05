/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.testkit.scaladsl

import akka.actor.ActorSystem
import akka.stream.Attributes.none
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit._

/**
 * Factory methods for test sinks.
 */
object TestSink {

  /**
   * A Sink that materialized to a [[akka.stream.testkit.TestSubscriber.Probe]].
   */
  def probe[T](implicit system: ActorSystem): Sink[T, Probe[T]] =
    new Sink[T, TestSubscriber.Probe[T]](new StreamTestKit.ProbeSink(none, SinkShape(Inlet("ProbeSink.in"))))

}
