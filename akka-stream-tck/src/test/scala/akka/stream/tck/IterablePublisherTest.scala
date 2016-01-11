/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import scala.collection.immutable
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams._

class IterablePublisherTest extends AkkaPublisherVerification[Int] {

  override def createPublisher(elements: Long): Publisher[Int] = {
    Source(iterable(elements)).runWith(Sink.asPublisher(false))
  }

}
