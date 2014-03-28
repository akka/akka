/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import akka.testkit.TestProbe

trait AkkaConsumerProbe[I] extends ConsumerProbe[I] {
  def probe: TestProbe
}
