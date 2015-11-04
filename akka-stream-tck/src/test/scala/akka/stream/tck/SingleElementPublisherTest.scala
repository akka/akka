/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.impl.SingleElementPublisher

import scala.collection.immutable
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams._

class SingleElementPublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    Source(SingleElementPublisher(0, "single-element-publisher")).runWith(Sink.publisher(false))
  }

  override def maxElementsFromPublisher(): Long = 1
}
