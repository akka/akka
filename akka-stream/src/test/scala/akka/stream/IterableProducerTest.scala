/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.tck.PublisherVerification
import scala.collection.immutable
import akka.stream.scaladsl.Flow

class IterableProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {

  val materializer = FlowMaterializer(MaterializerSettings(
    maximumInputBufferSize = 512))(system)

  def createPublisher(elements: Int): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == 0)
        new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else
        0 until elements
    Flow(iterable).toProducer(materializer).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    Flow[Int](Nil).toProducer(materializer).getPublisher

}