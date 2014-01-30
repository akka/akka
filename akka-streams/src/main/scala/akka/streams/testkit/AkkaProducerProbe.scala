package akka.streams.testkit

import rx.async.tck.ProducerProbe
import akka.testkit.TestProbe

trait AkkaProducerProbe[I] extends ProducerProbe[I] {
  def probe: TestProbe
}
