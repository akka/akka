package akka.streams

import akka.actor.ActorSystem

class ActorBasedImplementationSpec extends ImplementationFactorySpec
  with ImplementationFactoryOperationSpec
  with ImplementationFactoryProducerSpec {
  implicit lazy val system = ActorSystem()
  def factoryWithFanOutBuffer(capacity: Int): ImplementationFactory = new ActorBasedImplementationFactory(ActorBasedImplementationSettings(system, capacity))
}
