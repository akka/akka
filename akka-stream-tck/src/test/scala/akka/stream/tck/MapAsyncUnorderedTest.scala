/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.{ Flow, OperationAttributes }
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }
import org.reactivestreams.{ Processor, Publisher }

import scala.concurrent.Future

class MapAsyncUnorderedTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorFlowMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorFlowMaterializer(settings)(system)

    processorFromFlow(
      Flow[Int].mapAsyncUnordered(Future.successful).withAttributes(OperationAttributes.name("identity")))
  }

  override def createElement(element: Int): Int = element

}
