/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher

class FlattenTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val s1 = Source(iterable(elements / 2))
    val s2 = Source(iterable((elements + 1) / 2))
    Source(List(s1, s2)).flattenConcat().runWith(Sink.publisher)
  }

}
