/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit.javadsl

import akka.actor.ActorSystem
import akka.stream.javadsl.Sink
import akka.stream.testkit._

/** Java API */
object TestSink {

  /**
   * A Sink that materialized to a [[akka.stream.testkit.TestSubscriber.Probe]].
   */
  def probe[T](system: ActorSystem): Sink[T, TestSubscriber.Probe[T]] =
    new Sink(scaladsl.TestSink.probe[T](system))

}
