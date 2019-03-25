/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import scala.collection.immutable
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher

class FanoutPublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == 0) new immutable.Iterable[Int] { override def iterator = Iterator.from(0) } else
        0 until elements.toInt

    Source(iterable).runWith(Sink.asPublisher(true))
  }

}
