package akka.streams

import org.scalatest.testng.TestNGSuiteLike
import asyncrx.spi.Publisher
import asyncrx.tck.PublisherVerification

class IteratorProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {
  import system.dispatcher

  def createPublisher(elements: Int): Publisher[Int] = {
    val iter = Iterator from 1000
    TestProducer(if (elements > 0) iter take elements else iter).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    TestProducer(Nil).getPublisher
}