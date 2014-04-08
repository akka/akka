/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.tck.PublisherVerification
import akka.stream.scaladsl.Flow

class IteratorProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {

  val gen = FlowMaterializer(MaterializerSettings(
    maximumInputBufferSize = 512))(system)

  def createPublisher(elements: Int): Publisher[Int] = {
    val iter: Iterator[Int] =
      if (elements == 0)
        Iterator from 0
      else
        (Iterator from 0).take(elements)
    Flow(iter).toProducer(gen).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    Flow(List.empty[Int].iterator).toProducer(gen).getPublisher

}