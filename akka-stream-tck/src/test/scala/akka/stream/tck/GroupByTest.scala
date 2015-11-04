/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
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
        Source(iterable(elements)).groupBy(elem ⇒ "all").map { case (_, group) ⇒ group }.runWith(Sink.head)
      val groupSource = Await.result(futureGroupSource, 3.seconds)
      groupSource.runWith(Sink.publisher(false))

    }

}
