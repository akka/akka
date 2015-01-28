/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import java.util.concurrent.atomic.AtomicInteger

import akka.stream.impl.Stages.Identity
import akka.stream.scaladsl.{ OperationAttributes, Flow }
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }
import org.reactivestreams.{ Processor, Publisher }

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  val processorCounter = new AtomicInteger

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorFlowMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorFlowMaterializer(settings)(system)

    processorFromFlow(
      // withAttributes "wraps" the underlying identity and protects it from automatic removal
      Flow[Int].andThen(Identity()).withAttributes(OperationAttributes.name("identity")))
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = ActorFlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

}
