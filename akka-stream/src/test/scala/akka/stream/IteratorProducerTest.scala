/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.tck.PublisherVerification
import akka.stream.testkit.TestProducer

class IteratorProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {
  import system.dispatcher

  def createPublisher(elements: Int): Publisher[Int] = {
    val iter = Iterator from 1000
    TestProducer(if (elements > 0) iter take elements else iter).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    TestProducer(Nil).getPublisher
}