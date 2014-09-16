/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.Flow
import org.reactivestreams.Publisher

import scala.collection.immutable

class IteratorPublisherTest extends AkkaPublisherVerification[Int](true) {

  def createPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == 0) new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else 0 until elements.toInt

    Flow(iterable).toPublisher()
  }

}