/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.scaladsl.OperationAttributes
import scala.collection.breakOut
import akka.actor.Props
import akka.stream.scaladsl.FlexiRoute
import akka.stream.MaterializerSettings
import akka.stream.impl.FanOut.OutputBunch
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] object FlexiRouteImpl {
  def props(settings: MaterializerSettings, outputCount: Int, routeLogic: FlexiRoute.RouteLogic[Any]): Props =
    Props(new FlexiRouteImpl(settings, outputCount, routeLogic))

  trait RouteLogicFactory[In] {
    def attributes: OperationAttributes
    def createRouteLogic(): FlexiRoute.RouteLogic[In]
  }
}

/**
 * INTERNAL API
 */
private[akka] class FlexiRouteImpl(_settings: MaterializerSettings,
                                   outputCount: Int,
                                   routeLogic: FlexiRoute.RouteLogic[Any])
  extends FanOut(_settings, outputCount) {

  import FlexiRoute._

  val outputMapping: Map[Int, OutputHandle] =
    routeLogic.outputHandles(outputCount).take(outputCount).zipWithIndex.map(_.swap)(breakOut)

  private type StateT = routeLogic.State[Any]
  private type CompletionT = routeLogic.CompletionHandling

  private var behavior: StateT = _
  private var completion: CompletionT = _

  override protected val outputBunch = new OutputBunch(outputPorts, self, this) {
    override def onCancel(output: Int): Unit =
      changeBehavior(try completion.onDownstreamFinish(ctx, outputMapping(output))
      catch {
        case NonFatal(e) ⇒ fail(e); routeLogic.SameState
      })
  }

  override protected val primaryInputs: Inputs = new BatchingInputBuffer(settings.maxInputBufferSize, this) {
    override def onError(e: Throwable): Unit = {
      try completion.onUpstreamFailure(ctx, e) catch { case NonFatal(e) ⇒ fail(e) }
      fail(e)
    }

    override def onComplete(): Unit = {
      try completion.onUpstreamFinish(ctx) catch { case NonFatal(e) ⇒ fail(e) }
      super.onComplete()
    }
  }

  private val ctx: routeLogic.RouteLogicContext[Any] = new routeLogic.RouteLogicContext[Any] {
    override def isDemandAvailable(output: OutputHandle): Boolean =
      (output.portIndex < outputCount) && outputBunch.isPending(output.portIndex)

    override def emit(output: OutputHandle, elem: Any): Unit = {
      require(outputBunch.isPending(output.portIndex), s"emit to [$output] not allowed when no demand available")
      outputBunch.enqueue(output.portIndex, elem)
    }

    override def finish(): Unit = {
      primaryInputs.cancel()
      outputBunch.complete()
      context.stop(self)
    }

    override def finish(output: OutputHandle): Unit =
      outputBunch.complete(output.portIndex)

    override def fail(cause: Throwable): Unit = FlexiRouteImpl.this.fail(cause)

    override def fail(output: OutputHandle, cause: Throwable): Unit =
      outputBunch.error(output.portIndex, cause)

    override def changeCompletionHandling(newCompletion: CompletionT): Unit =
      FlexiRouteImpl.this.changeCompletionHandling(newCompletion)

  }

  private def markOutputs(outputs: Array[OutputHandle]): Unit = {
    outputBunch.unmarkAllOutputs()
    var i = 0
    while (i < outputs.length) {
      val id = outputs(i).portIndex
      if (outputMapping.contains(id) && !outputBunch.isCancelled(id) && !outputBunch.isCompleted(id))
        outputBunch.markOutput(id)
      i += 1
    }
  }

  private def precondition: TransferState = {
    behavior.condition match {
      case _: DemandFrom | _: DemandFromAny ⇒ primaryInputs.NeedsInput && outputBunch.AnyOfMarkedOutputs
      case _: DemandFromAll                 ⇒ primaryInputs.NeedsInput && outputBunch.AllOfMarkedOutputs
    }
  }

  private def changeCompletionHandling(newCompletion: CompletionT): Unit =
    completion = newCompletion.asInstanceOf[CompletionT]

  private def changeBehavior[A](newBehavior: routeLogic.State[A]): Unit =
    if (newBehavior != routeLogic.SameState && (newBehavior ne behavior)) {
      behavior = newBehavior.asInstanceOf[StateT]
      behavior.condition match {
        case any: DemandFromAny ⇒
          markOutputs(any.outputs.toArray)
        case all: DemandFromAll ⇒
          markOutputs(all.outputs.toArray)
        case DemandFrom(output) ⇒
          require(outputMapping.contains(output.portIndex), s"Unknown output handle $output")
          require(!outputBunch.isCancelled(output.portIndex), s"Demand not allowed from cancelled $output")
          require(!outputBunch.isCompleted(output.portIndex), s"Demand not allowed from completed $output")
          outputBunch.unmarkAllOutputs()
          outputBunch.markOutput(output.portIndex)
      }
    }

  changeBehavior(routeLogic.initialState)
  changeCompletionHandling(routeLogic.initialCompletionHandling)

  nextPhase(TransferPhase(precondition) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    behavior.condition match {
      case any: DemandFromAny ⇒
        val id = outputBunch.idToEnqueueAndYield()
        val outputHandle = outputMapping(id)
        changeBehavior(behavior.onInput(ctx, outputHandle, elem))

      case DemandFrom(outputHandle) ⇒
        changeBehavior(behavior.onInput(ctx, outputHandle, elem))

      case all: DemandFromAll ⇒
        val id = outputBunch.idToEnqueueAndYield()
        val outputHandle = outputMapping(id)
        changeBehavior(behavior.onInput(ctx, outputHandle, elem))

    }

  })

}
