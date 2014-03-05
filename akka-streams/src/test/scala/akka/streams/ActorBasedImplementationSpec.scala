package akka.streams

import akka.actor.ActorSystem

class ActorBasedStreamGeneratorSpec extends StreamGeneratorSpec
  with ImplementationFactoryOperationSpec
  with ImplementationFactoryProducerSpec
  with ImplementationFactoryConsumerSpec {
  implicit lazy val system = ActorSystem()
  def factoryWithFanOutBuffer(capacity: Int): StreamGenerator = new ActorBasedStreamGenerator(ActorBasedStreamGeneratorSettings(system, maxFanOutBufferSize = capacity))
}
