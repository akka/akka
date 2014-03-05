package akka.streams

trait ImplementationFactoryConsumerSpec extends StreamGeneratorSpec {
  "A consumer built by an ImplementationFactory" - {
    "after being subscribed to a producer" - {
      "receives elements" in pending
    }
    "should throw an error when subscribed more than once" in pending
  }
}
