package akka.streams.testkit

import rx.async.tck.ConsumerProbe
import akka.testkit.TestProbe

trait AkkaConsumerProbe[I] extends ConsumerProbe[I] {
  def probe: TestProbe
}
