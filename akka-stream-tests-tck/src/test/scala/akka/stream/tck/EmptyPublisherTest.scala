/**
 * Copyright (C) 2014-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import org.reactivestreams.Publisher
import akka.stream.impl.EmptyPublisher

class EmptyPublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = EmptyPublisher[Int]

  override def maxElementsFromPublisher(): Long = 0
}

