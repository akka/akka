/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import akka.NotUsed
import akka.stream._
import akka.stream.impl.fusing.GraphStages
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorMaterializer(settings)(system)

    // withAttributes "wraps" the underlying identity and protects it from automatic removal
    Flow[Int].via(GraphStages.Identity.asInstanceOf[Graph[FlowShape[Int, Int], NotUsed]]).named("identity").toProcessor.run()
  }

  override def createElement(element: Int): Int = element

}
