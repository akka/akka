/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams._

class IterablePublisherTest extends AkkaPublisherVerification[Int] {

  override def createPublisher(elements: Long): Publisher[Int] = {
    Source(iterable(elements)).runWith(Sink.asPublisher(false))
  }

}
