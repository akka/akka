package akka.stream.impl

import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }

import scala.util.control.NonFatal

private[stream] final class JavaStreamSource[T, S <: java.util.stream.BaseStream[T, S]](open: () ⇒ java.util.stream.BaseStream[T, S])
  extends GraphStage[SourceShape[T]] {

  private[this] val out: Outlet[T] = Outlet("JavaStreamSource")
  override val shape: SourceShape[T] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      private[this] var stream: java.util.stream.BaseStream[T, S] = _
      private[this] var iter: java.util.Iterator[T] = _

      override def preStart(): Unit = {
        stream = open()
        iter = stream.iterator()
      }

      override def postStop(): Unit = {
        if (stream ne null)
          stream.close()
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          try if (iter.hasNext) {
            push(out, iter.next())
          } else {
            complete(out)
          } catch {
            case NonFatal(e) ⇒ fail(out, e)
          }
        }
      })
    }
}
