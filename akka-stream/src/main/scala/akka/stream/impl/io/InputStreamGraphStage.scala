package akka.stream.impl.io

import java.io.InputStream

import akka.stream.scaladsl.Source
import akka.stream.{ Attributes, IOResult, SourceShape }
import akka.stream.stage.{ GraphStageLogic, GraphStageLogicWithLogging, GraphStageWithMaterializedValue, OutHandler }
import akka.util.ByteString

import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 */
private[akka] class InputStreamGraphStage(factory: () => InputStream, chunkSize: Int)
    extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[IOResult]] {

  private val _shape = Source.shape("InputStream")

  /**
   * The shape of a graph is all that is externally visible: its inlets and outlets.
   */
  override def shape: SourceShape[ByteString] = _shape

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val mat = Promise[IOResult]
    val logic = new GraphStageLogicWithLogging(shape) with OutHandler {
      override def onPull(): Unit = ???
    }
    (logic, mat.future)
  }
}
