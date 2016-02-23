/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.stream.impl.EmptyPublisher
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher

class GroupByTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] =
    if (elements == 0) EmptyPublisher[Int]
    else {
      val futureGroupSource =
        Source(iterable(elements))
          .groupBy(1, elem ⇒ "all")
          .prefixAndTail(0)
          .map(_._2)
          .concatSubstreams
          .runWith(Sink.head)
      val groupSource = Await.result(futureGroupSource, 3.seconds)
      groupSource.runWith(Sink.asPublisher(false))

    }

}
