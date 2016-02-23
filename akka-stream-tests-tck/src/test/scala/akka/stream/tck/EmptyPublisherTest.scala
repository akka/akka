/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import akka.stream.impl.EmptyPublisher

class EmptyPublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = EmptyPublisher[Int]

  override def maxElementsFromPublisher(): Long = 0
}

