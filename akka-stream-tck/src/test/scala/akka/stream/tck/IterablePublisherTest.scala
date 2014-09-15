/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.Flow
import org.reactivestreams._

import scala.collection.immutable

class IterablePublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == Long.MaxValue)
        new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else
        0 until elements.toInt

    Flow(iterable).toPublisher()
  }

}