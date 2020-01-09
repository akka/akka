/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import org.reactivestreams.Publisher

class PrefixAndDownstreamTest extends AkkaPublisherVerification[Int] {
  override def createPublisher(elements: Long): Publisher[Int] = {
    val publisher = Source(iterable(elements))
      .map(_.toInt)
      .flatMapPrefixMat(1) { seq =>
        Flow[Int].prepend(Source(seq))
      }(Keep.left)
      .runWith(Sink.asPublisher(false))
    publisher
  }
}
