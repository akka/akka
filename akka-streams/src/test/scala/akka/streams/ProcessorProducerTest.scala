package akka.streams

import org.scalatest.testng.TestNGSuiteLike
import rx.async.spi.Publisher
import rx.async.tck.PublisherVerification
import akka.streams.Operation._

class ProcessorProducerTest extends PublisherVerification[Int] with WithActorSystem with TestNGSuiteLike {
  implicit val abif = new ActorBasedImplementationFactory(ActorBasedImplementationSettings(system))
  def createPublisher(elements: Int): Publisher[Int] = {
    val producer = Producer(Iterator from 1000 take elements).map(identity).toProducer()
    producer.getPublisher
  }
  override def createCompletedStatePublisher(): Publisher[Int] = Source.empty[Int].toProducer().getPublisher
}
