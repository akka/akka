package akka.streams

import akka.actor.ActorSystem
import akka.streams.impl.RaceTrack
import org.scalatest.WordSpec

class ActorBasedImplementationSpec extends ImplementationFactorySpec
  with ImplementationFactoryOperationSpec
  with ImplementationFactoryProducerSpec {
  implicit lazy val system = ActorSystem()
  lazy val settings: ActorBasedImplementationSettings = ActorBasedImplementationSettings(system, () ⇒ new RaceTrack(bufferSize = 1))

  lazy val factory: ImplementationFactory = new ActorBasedImplementationFactory(settings)
}
