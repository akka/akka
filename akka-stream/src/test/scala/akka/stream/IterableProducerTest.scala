/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.tck.PublisherVerification
import scala.collection.immutable

class IterableProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {

  val gen = ProcessorGenerator(GeneratorSettings(
    maximumInputBufferSize = 512))(system)

  def createPublisher(elements: Int): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == 0)
        new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else
        0 until elements
    Stream(iterable).toProducer(gen).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    Stream(Nil).toProducer(gen).getPublisher

}