/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream._
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    Flow[Int]
      .map(identity)
      .toProcessor
      .withAttributes(Attributes.inputBuffer(initial = maxBufferSize / 2, max = maxBufferSize))
      .run()
  }

  override def createElement(element: Int): Int = element

}
