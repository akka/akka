/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams._

import scala.concurrent.Promise

class FuturePublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val p = Promise[Int]()
    val pub = Source(p.future).runWith(Sink.publisher(false))
    p.success(0)
    pub
  }

  override def maxElementsFromPublisher(): Long = 1
}
