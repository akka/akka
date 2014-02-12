package akka.streams

import akka.actor.ActorSystem
import akka.streams.impl.RaceTrack

class ActorBasedImplementationSpec extends ImplementationFactorySpec {
  implicit lazy val system = ActorSystem()
  lazy val settings: ActorBasedImplementationSettings = ActorBasedImplementationSettings(system, () ⇒ new RaceTrack(bufferSize = 1))

  lazy val factory: ImplementationFactory = new ActorBasedImplementationFactory(settings)
}
