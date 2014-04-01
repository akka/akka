/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.tck.PublisherVerification

class IteratorProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {

  val gen = ProcessorGenerator(GeneratorSettings(
    maximumInputBufferSize = 512))(system)

  def createPublisher(elements: Int): Publisher[Int] = {
    val iter: Iterator[Int] =
      if (elements == 0)
        Iterator from 0
      else
        (Iterator from 0).take(elements)
    Stream(iter).toProducer(gen).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    Stream(List.empty[Int].iterator).toProducer(gen).getPublisher

}