/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import akka.testkit.TestProbe

trait AkkaProducerProbe[I] extends ProducerProbe[I] {
  def probe: TestProbe
}
