/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import org.reactivestreams.Publisher

class SingleElementSourceTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] =
    Source.single(1).runWith(Sink.publisher(false))

  override def maxElementsFromPublisher(): Long = 1
}

