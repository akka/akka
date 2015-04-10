/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor
import akka.stream.OperationAttributes

class MapTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    implicit val materializer = ActorFlowMaterializer()(system)

    processorFromFlow(
      Flow[Int].map(elem ⇒ elem).withAttributes(OperationAttributes.name("identity")))
  }

  override def createElement(element: Int): Int = element

}
