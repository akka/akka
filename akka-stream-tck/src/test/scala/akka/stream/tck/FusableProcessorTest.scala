/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }
import akka.stream.impl.Stages.Identity
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor
import akka.stream.Attributes

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorFlowMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorFlowMaterializer(settings)(system)

    processorFromFlow(
      // withAttributes "wraps" the underlying identity and protects it from automatic removal
      Flow[Int].andThen(Identity()).named("identity"))
  }

  override def createElement(element: Int): Int = element

}
