package akka.streams

import org.scalatest.testng.TestNGSuiteLike
import rx.async.spi.Publisher
import rx.async.tck.PublisherVerification

class IteratorProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {
  import system.dispatcher

  def createPublisher(elements: Int): Publisher[Int] = {
    val iter = Iterator from 1000
    Producer(if (elements > 0) iter take elements else iter).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    Producer(Nil).getPublisher
}