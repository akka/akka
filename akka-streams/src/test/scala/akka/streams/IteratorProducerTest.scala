package akka.streams

import org.scalatest.testng.TestNGSuiteLike
import rx.async.spi.Publisher
import rx.async.tck.PublisherVerification

class IteratorProducerTest extends PublisherVerification[Int] with TestNGSuiteLike {

  def createPublisher(elements: Int): Publisher[Int] = {
    require(elements > 0)
    Producer(Iterator from 1000 take elements).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    Producer(Nil).getPublisher
}