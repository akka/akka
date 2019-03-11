/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.stream.impl.EmptyPublisher
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher

class SplitWhenTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] =
    if (elements == 0) EmptyPublisher[Int]
    else {
      val futureSource =
        Source(iterable(elements))
          .splitWhen(elem => false)
          .prefixAndTail(0)
          .map(_._2)
          .concatSubstreams
          .runWith(Sink.head)
      val source = Await.result(futureSource, 3.seconds)
      source.runWith(Sink.asPublisher(false))
    }

}
