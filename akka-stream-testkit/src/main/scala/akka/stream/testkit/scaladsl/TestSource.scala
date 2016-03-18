/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.testkit.scaladsl

import akka.stream._
import akka.stream.Attributes.none
import akka.stream.scaladsl._
import akka.stream.testkit._

import akka.actor.ActorSystem;

/**
 * Factory methods for test sources.
 */
object TestSource {

  /**
   * A Source that materializes to a [[akka.stream.testkit.TestPublisher.Probe]].
   */
  def probe[T](implicit system: ActorSystem) = new Source[T, TestPublisher.Probe[T]](new StreamTestKit.ProbeSource(none, SourceShape(Outlet("ProbeSource.out"))))

}
