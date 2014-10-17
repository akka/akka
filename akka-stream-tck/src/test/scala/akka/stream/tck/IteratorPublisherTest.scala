/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import scala.collection.immutable
import akka.stream.scaladsl2.Sink
import akka.stream.scaladsl2.Source
import org.reactivestreams.Publisher

class IteratorPublisherTest extends AkkaPublisherVerification[Int](true) {

  def createPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == 0) new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else 0 until elements.toInt

    Source(iterable).runWith(Sink.publisher)
  }

}