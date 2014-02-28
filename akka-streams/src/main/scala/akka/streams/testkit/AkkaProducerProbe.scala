package akka.streams.testkit

import akka.testkit.TestProbe

trait AkkaProducerProbe[I] extends ProducerProbe[I] {
  def probe: TestProbe
}
