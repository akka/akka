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
      if (elements == Long.MaxValue)
        new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else
        0 until elements.toInt

    Source(SynchronousIterablePublisher(iterable, "synchronous-iterable-publisher")).runWith(Sink.publisher())
  }

  override def spec317_mustSignalOnErrorWhenPendingAboveLongMaxValue() = notVerified("RS TCK 1.0.0.M3 does not handle sync publishers well")
}