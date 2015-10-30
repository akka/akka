/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import akka.stream.scaladsl.{ Keep, Source, Sink }

class MaybeSourceTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val (p, pub) = Source.maybe[Int].toMat(Sink.publisher(1))(Keep.both).run()
    p success Some(1)
    pub
  }

  override def maxElementsFromPublisher(): Long = 1
}

