package akka.streams.testkit

import akka.testkit.TestProbe

trait AkkaConsumerProbe[I] extends ConsumerProbe[I] {
  def probe: TestProbe
}
