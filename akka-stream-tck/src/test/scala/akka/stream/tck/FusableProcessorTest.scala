/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import java.util.concurrent.atomic.AtomicInteger
import akka.stream.impl.{ Ast, ActorFlowMaterializerImpl }
import akka.stream.scaladsl.MaterializedMap
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }
import org.reactivestreams.{ Publisher, Processor }
import akka.stream.impl.fusing.Map
import scala.concurrent.Promise
import akka.stream.Supervision

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  val processorCounter = new AtomicInteger

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorFlowMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorFlowMaterializer(settings)(system)

    val flowName = getClass.getSimpleName + "-" + processorCounter.incrementAndGet()

    val (processor, _ns) = materializer.asInstanceOf[ActorFlowMaterializerImpl].processorForNode(
      Ast.Fused(List(Map[Int, Int](identity, Supervision.stoppingDecider)), name("identity")), flowName, 1)

    processor.asInstanceOf[Processor[Int, Int]]
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = ActorFlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

}
