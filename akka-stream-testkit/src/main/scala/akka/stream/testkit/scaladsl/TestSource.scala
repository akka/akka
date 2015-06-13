/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit.scaladsl

import akka.stream._
import akka.stream.impl._
import akka.stream.Attributes.none
import akka.stream.scaladsl._
import akka.stream.testkit._

import akka.actor.ActorSystem;

/**
 * Factory methods for test sources.
 */
object TestSource {

  /**
   * A Source that materializes to a [[TestPublisher.Probe]].
   */
  def probe[T]()(implicit system: ActorSystem) = new Source[T, TestPublisher.Probe[T]](new StreamTestKit.ProbeSource(none, SourceShape(Outlet("ProbeSource.out"))))

}
