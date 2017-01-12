/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Attributes }
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import org.reactivestreams.Processor

class TransformProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorMaterializer(settings)(system)

    val stage =
      new SimpleLinearGraphStage[Int] {
        override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
          override def onPush(): Unit = push(out, grab(in))
          override def onPull(): Unit = pull(in)
          setHandlers(in, out, this)
        }
      }

    Flow[Int].via(stage).toProcessor.run()
  }

  override def createElement(element: Int): Int = element

}
