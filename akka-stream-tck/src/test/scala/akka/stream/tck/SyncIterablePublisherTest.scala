/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.impl.SynchronousIterablePublisher

import scala.collection.immutable
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams._

class SyncIterablePublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements >= 10000)
        0 until 10000 // this publisher is not intended to be used for large collections
      else
        0 until elements.toInt

    Source(SynchronousIterablePublisher(iterable, "synchronous-iterable-publisher")).runWith(Sink.publisher)
  }

}
