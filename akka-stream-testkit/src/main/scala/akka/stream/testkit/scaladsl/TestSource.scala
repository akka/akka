/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit.scaladsl

import akka.stream._
import akka.stream.Attributes.none
import akka.stream.scaladsl._
import akka.stream.testkit._

import akka.actor.ActorSystem

/**
 * Factory methods for test sources.
 */
object TestSource {

  /**
   * A Source that materializes to a [[akka.stream.testkit.TestPublisher.Probe]].
   */
  def probe[T](implicit system: ActorSystem) = Source.fromGraph[T, TestPublisher.Probe[T]](new StreamTestKit.ProbeSource(none, SourceShape(Outlet("ProbeSource.out"))))

}
