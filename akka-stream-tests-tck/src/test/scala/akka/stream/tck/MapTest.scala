/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor

class MapTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    Flow[Int].map(elem => elem).named("identity").toProcessor.run()
  }

  override def createElement(element: Int): Int = element

}
