/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import java.util.concurrent.atomic.AtomicInteger

import akka.stream.scaladsl.{ Flow, OperationAttributes }
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }
import org.reactivestreams.{ Processor, Publisher }

import scala.concurrent.Future

class MapAsyncTest extends AkkaIdentityProcessorVerification[Int] {

  val processorCounter = new AtomicInteger

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorFlowMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorFlowMaterializer(settings)(system)

    processorFromFlow(
      Flow[Int].mapAsync(Future.successful).withAttributes(OperationAttributes.name("identity")))
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = ActorFlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

}
