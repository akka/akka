/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit.javadsl

import akka.actor.ActorSystem
import akka.actor.ClassicActorSystemProvider
import akka.stream.javadsl.Source
import akka.stream.testkit._

/** Java API */
object TestSource {

  /**
   * A Source that materializes to a [[akka.stream.testkit.TestPublisher.Probe]].
   */
  def probe[T](system: ActorSystem): Source[T, TestPublisher.Probe[T]] =
    new Source(scaladsl.TestSource.probe[T](system))

  /**
   * A Source that materializes to a [[akka.stream.testkit.TestPublisher.Probe]].
   */
  def create[T](system: ClassicActorSystemProvider): Source[T, TestPublisher.Probe[T]] =
    probe(system.classicSystem)

}
