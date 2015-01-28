/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import java.util.concurrent.atomic.AtomicInteger

import akka.stream.impl.Stages.Identity
import akka.stream.scaladsl.{ OperationAttributes, Flow }
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import org.reactivestreams.{ Processor, Publisher }

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  val processorCounter = new AtomicInteger

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = MaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = FlowMaterializer(settings)(system)

    processorFromFlow(
      // withAttributes "wraps" the underlying identity and protects it from automatic removal
      Flow[Int].andThen(Identity()).withAttributes(OperationAttributes.name("identity")))
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = FlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

}
