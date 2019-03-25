/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

/**
 * Allows coupling termination (cancellation, completion, erroring) of Sinks and Sources while creating a Flow them them.
 * Similar to `Flow.fromSinkAndSource` however that API does not connect the completion signals of the wrapped operators.
 */
object CoupledTerminationFlow {

  /**
   * Similar to [[Flow.fromSinkAndSource]] however couples the termination of these two operators.
   *
   * E.g. if the emitted [[Flow]] gets a cancellation, the [[Source]] of course is cancelled,
   * however the Sink will also be completed. The table below illustrates the effects in detail:
   *
   * <table>
   *   <tr>
   *     <th>Returned Flow</th>
   *     <th>Sink (<code>in</code>)</th>
   *     <th>Source (<code>out</code>)</th>
   *   </tr>
   *   <tr>
   *     <td><i>cause:</i> upstream (sink-side) receives completion</td>
   *     <td><i>effect:</i> receives completion</td>
   *     <td><i>effect:</i> receives cancel</td>
   *   </tr>
   *   <tr>
   *     <td><i>cause:</i> upstream (sink-side) receives error</td>
   *     <td><i>effect:</i> receives error</td>
   *     <td><i>effect:</i> receives cancel</td>
   *   </tr>
   *   <tr>
   *     <td><i>cause:</i> downstream (source-side) receives cancel</td>
   *     <td><i>effect:</i> completes</td>
   *     <td><i>effect:</i> receives cancel</td>
   *   </tr>
   *   <tr>
   *     <td><i>effect:</i> cancels upstream, completes downstream</td>
   *     <td><i>effect:</i> completes</td>
   *     <td><i>cause:</i> signals complete</td>
   *   </tr>
   *   <tr>
   *     <td><i>effect:</i> cancels upstream, errors downstream</td>
   *     <td><i>effect:</i> receives error</td>
   *     <td><i>cause:</i> signals error or throws</td>
   *   </tr>
   *   <tr>
   *     <td><i>effect:</i> cancels upstream, completes downstream</td>
   *     <td><i>cause:</i> cancels</td>
   *     <td><i>effect:</i> receives cancel</td>
   *   </tr>
   * </table>
   *
   * The order in which the `in` and `out` sides receive their respective completion signals is not defined, do not rely on its ordering.
   */
  @deprecated("Use `Flow.fromSinkAndSourceCoupledMat(..., ...)(Keep.both)` instead", "2.5.2")
  def fromSinkAndSource[I, O, M1, M2](in: Sink[I, M1], out: Source[O, M2]): Flow[I, O, (M1, M2)] =
    Flow.fromSinkAndSourceCoupledMat(in, out)(Keep.both)

}

/** INTERNAL API */
private[stream] class CoupledTerminationBidi[I, O] extends GraphStage[BidiShape[I, I, O, O]] {
  val in1: Inlet[I] = Inlet("CoupledCompletion.in1")
  val out1: Outlet[I] = Outlet("CoupledCompletion.out1")
  val in2: Inlet[O] = Inlet("CoupledCompletion.in2")
  val out2: Outlet[O] = Outlet("CoupledCompletion.out2")
  override val shape: BidiShape[I, I, O, O] = BidiShape(in1, out1, in2, out2)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    val handler1 = new InHandler with OutHandler {
      override def onPush(): Unit = push(out1, grab(in1))
      override def onPull(): Unit = pull(in1)

      override def onDownstreamFinish(): Unit = completeStage()
      override def onUpstreamFinish(): Unit = completeStage()
      override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
    }

    val handler2 = new InHandler with OutHandler {
      override def onPush(): Unit = push(out2, grab(in2))
      override def onPull(): Unit = pull(in2)

      override def onDownstreamFinish(): Unit = completeStage()
      override def onUpstreamFinish(): Unit = completeStage()
      override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
    }

    setHandlers(in1, out1, handler1)
    setHandlers(in2, out2, handler2)
  }
}
