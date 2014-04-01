/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.tck.PublisherVerification
import akka.stream.impl.ActorBasedProcessorGenerator
import org.reactivestreams.api.Producer
import akka.stream.scala_api.Flow

class ActorProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {
  import system.dispatcher

  private val factory = ProcessorGenerator(GeneratorSettings())

  private def createProducer(elements: Int): Producer[Int] = {
    val iter = Iterator from 1000
    val iter2 = if (elements > 0) iter take elements else iter
    Flow(factory, () ⇒ if (iter2.hasNext) iter2.next() else throw Stop).toProducer(factory)
  }

  def createPublisher(elements: Int): Publisher[Int] = createProducer(elements).getPublisher

  override def createCompletedStatePublisher(): Publisher[Int] = {
    val pub = createProducer(1)
    Flow(pub).consume(factory)
    Thread.sleep(100)
    pub.getPublisher
  }

}