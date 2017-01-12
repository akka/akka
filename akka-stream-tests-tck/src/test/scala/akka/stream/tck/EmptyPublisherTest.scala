/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import org.reactivestreams.Publisher
import akka.stream.impl.EmptyPublisher

class EmptyPublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = EmptyPublisher[Int]

  override def maxElementsFromPublisher(): Long = 0
}

