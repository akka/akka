/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream._
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorMaterializer(settings)(system)

    Flow[Int].map(identity).toProcessor.run()
  }

  override def createElement(element: Int): Int = element

}
