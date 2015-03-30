/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.impl.MaterializedValuePublisher
import org.reactivestreams.Publisher

class MaterializedValuePublisherTest extends AkkaPublisherVerification[Any] {

  override def createPublisher(elements: Long): Publisher[Any] = {
    val pub = new MaterializedValuePublisher()

    // it contains a value already
    pub.setValue("Hello")

    pub
  }

  override def maxElementsFromPublisher = 1
}