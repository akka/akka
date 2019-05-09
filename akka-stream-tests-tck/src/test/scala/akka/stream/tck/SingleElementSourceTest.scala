/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import org.reactivestreams.Publisher

class SingleElementSourceTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] =
    Source.single(1).runWith(Sink.asPublisher(false))

  override def maxElementsFromPublisher(): Long = 1
}
