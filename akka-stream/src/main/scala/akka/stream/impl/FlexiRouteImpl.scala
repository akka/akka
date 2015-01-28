/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.{ scaladsl, MaterializerSettings }
import akka.stream.impl.FanOut.OutputBunch
import akka.stream.{ Shape, OutPort, Outlet }

import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] class FlexiRouteImpl[T, S <: Shape](_settings: MaterializerSettings,
                                                  shape: S,
                                                  routeLogic: scaladsl.FlexiRoute.RouteLogic[T])
  extends FanOut(_settings, shape.outlets.size) {

  import akka.stream.scaladsl.FlexiRoute._

  private type StateT = routeLogic.State[_]
  private type CompletionT = routeLogic.CompletionHandling

  val outputMapping: Array[Outlet[_]] = shape.outlets.toArray
  val indexOf: Map[OutPort, Int] = shape.outlets.zipWithIndex.toMap

  private def anyBehavior = behavior.asInstanceOf[routeLogic.State[Outlet[Any]]]
  private var behavior: StateT = _
  private var completion: CompletionT = _

  override protected val outputBunch = new OutputBunch(outputCount, self, this) {
    override def onCancel(output: Int): Unit =
      changeBehavior(
        try completion.onCancel(ctx, outputMapping(output))
        catch {
          case NonFatal(e) ⇒ fail(e); routeLogic.SameState
        })
  }

  override protected val primaryInputs: Inputs = new BatchingInputBuffer(settings.maxInputBufferSize, this) {
    override def onError(e: Throwable): Unit = {
      try completion.onError(ctx, e) catch { case NonFatal(e) ⇒ fail(e) }
      fail(e)
    }

    override def onComplete(): Unit = {
      try completion.onComplete(ctx) catch { case NonFatal(e) ⇒ fail(e) }
      super.onComplete()
    }
  }

  private val ctx: routeLogic.RouteLogicContext = new routeLogic.RouteLogicContext {
    override def isDemandAvailable(output: OutPort): Boolean =
      (indexOf(output) < outputCount) && outputBunch.isPending(indexOf(output))

    override def emit[Out](output: Outlet[Out])(elem: Out): Unit = {
      val idx = indexOf(output)
      require(outputBunch.isPending(idx), s"emit to [$output] not allowed when no demand available")
      outputBunch.enqueue(idx, elem)
    }

    override def complete(): Unit = {
      primaryInputs.cancel()
      outputBunch.complete()
      context.stop(self)
    }

    override def complete(output: OutPort): Unit =
      outputBunch.complete(indexOf(output))

    override def error(cause: Throwable): Unit = fail(cause)

    override def error(output: OutPort, cause: Throwable): Unit =
      outputBunch.error(indexOf(output), cause)

    override def changeCompletionHandling(newCompletion: CompletionT): Unit =
      FlexiRouteImpl.this.changeCompletionHandling(newCompletion)

  }

  private def markOutputs(outputs: Array[OutPort]): Unit = {
    outputBunch.unmarkAllOutputs()
    var i = 0
    while (i < outputs.length) {
      val id = indexOf(outputs(i))
      if (!outputBunch.isCancelled(id) && !outputBunch.isCompleted(id))
        outputBunch.markOutput(id)
      i += 1
    }
  }

  private def precondition: TransferState = {
    behavior.condition match {
      case _: DemandFrom[_] | _: DemandFromAny ⇒ primaryInputs.NeedsInput && outputBunch.AnyOfMarkedOutputs
      case _: DemandFromAll                    ⇒ primaryInputs.NeedsInput && outputBunch.AllOfMarkedOutputs
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
          require(indexOf.contains(output), s"Unknown output handle $output")
          val idx = indexOf(output)

          require(!outputBunch.isCancelled(idx), s"Demand not allowed from cancelled $output")
          require(!outputBunch.isCompleted(idx), s"Demand not allowed from completed $output")
          outputBunch.unmarkAllOutputs()
          outputBunch.markOutput(idx)
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
        changeBehavior(anyBehavior.onInput(ctx, outputHandle, elem.asInstanceOf[T]))

      case DemandFrom(outputHandle) ⇒
        changeBehavior(anyBehavior.onInput(ctx, outputHandle, elem.asInstanceOf[T]))

      case all: DemandFromAll ⇒
        changeBehavior(behavior.asInstanceOf[routeLogic.State[Unit]].onInput(ctx, (), elem.asInstanceOf[T]))

    }

  })

}
