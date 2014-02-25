package akka.streams

import org.scalatest.testng.TestNGSuiteLike
import rx.async.spi.Publisher
import rx.async.api.Processor
import rx.async.tck.IdentityProcessorVerification
import akka.streams.Operation._

class IdentityProcessorTest extends IdentityProcessorVerification[Int] with WithActorSystem with TestNGSuiteLike {
  implicit val abif = new ActorBasedImplementationFactory(ActorBasedImplementationSettings(system))

  def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = abif.toProcessor(Identity[Int]())

  def createPublisher(elements: Int): Publisher[Int] = Producer(Iterator from 1000 take elements).getPublisher
}
