/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher

class ConcatTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    Source(iterable(elements / 2)).concat(Source(iterable((elements + 1) / 2))).runWith(Sink.publisher(false))
  }

}
