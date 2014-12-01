/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.Props
import akka.stream.MaterializerSettings
import akka.stream.scaladsl.OperationAttributes
import akka.stream.scaladsl.FlexiMerge

import scala.collection.breakOut

/**
 * INTERNAL API
 */
private[akka] object FlexiMergeImpl {
  def props(settings: MaterializerSettings, inputCount: Int, mergeLogic: FlexiMerge.MergeLogic[Any]): Props =
    Props(new FlexiMergeImpl(settings, inputCount, mergeLogic))

  trait MergeLogicFactory[Out] {
    def attributes: OperationAttributes
    def createMergeLogic(): FlexiMerge.MergeLogic[Out]
  }
}

/**
 * INTERNAL API
 */
private[akka] class FlexiMergeImpl(_settings: MaterializerSettings,
                                   inputCount: Int,
                                   mergeLogic: FlexiMerge.MergeLogic[Any])
  extends FanIn(_settings, inputCount) {

  import FlexiMerge._

  val inputMapping: Map[Int, InputHandle] =
    mergeLogic.inputHandles(inputCount).take(inputCount).zipWithIndex.map(_.swap)(breakOut)

  private type StateT = mergeLogic.State[Any]
  private type CompletionT = mergeLogic.CompletionHandling

  private var behavior: StateT = _
  private var completion: CompletionT = _

  override protected val inputBunch = new FanIn.InputBunch(inputPorts, settings.maxInputBufferSize, this) {
    override def onError(input: Int, e: Throwable): Unit = {
      changeBehavior(completion.onError(ctx, inputMapping(input), e))
      cancel(input)
    }

    override def onDepleted(input: Int): Unit =
      triggerCompletion(inputMapping(input))
  }

  private val ctx: mergeLogic.MergeLogicContext = new mergeLogic.MergeLogicContext {
    override def isDemandAvailable: Boolean = primaryOutputs.demandAvailable

    override def emit(elem: Any): Unit = {
      require(primaryOutputs.demandAvailable, "emit not allowed when no demand available")
      primaryOutputs.enqueueOutputElement(elem)
    }

    override def complete(): Unit = {
      inputBunch.cancel()
      primaryOutputs.complete()
      context.stop(self)
    }

    override def error(cause: Throwable): Unit = fail(cause)

    override def cancel(input: InputHandle): Unit = inputBunch.cancel(input.portIndex)

    override def changeCompletionHandling(newCompletion: CompletionT): Unit =
      FlexiMergeImpl.this.changeCompletionHandling(newCompletion)

  }

  private def markInputs(inputs: Array[InputHandle]): Unit = {
    inputBunch.unmarkAllInputs()
    var i = 0
    while (i < inputs.length) {
      val id = inputs(i).portIndex
      if (include(id))
        inputBunch.markInput(id)
      i += 1
    }
  }

  private def include(portIndex: Int): Boolean =
    inputMapping.contains(portIndex) && !inputBunch.isCancelled(portIndex) && !inputBunch.isDepleted(portIndex)

  private def precondition: TransferState = {
    behavior.condition match {
      case _: ReadAny | _: ReadPreferred | _: Read ⇒ inputBunch.AnyOfMarkedInputs && primaryOutputs.NeedsDemand
      case _: ReadAll                              ⇒ inputBunch.AllOfMarkedInputs && primaryOutputs.NeedsDemand
    }
  }

  private def changeCompletionHandling(newCompletion: CompletionT): Unit =
    completion = newCompletion.asInstanceOf[CompletionT]

  private def changeBehavior[A](newBehavior: mergeLogic.State[A]): Unit =
    if (newBehavior != mergeLogic.SameState && (newBehavior ne behavior)) {
      behavior = newBehavior.asInstanceOf[StateT]
      behavior.condition match {
        case read: ReadAny ⇒
          markInputs(read.inputs.toArray)
        case ReadPreferred(preferred, secondaries) ⇒
          markInputs(secondaries.toArray)
          inputBunch.markInput(preferred.portIndex)
        case read: ReadAll ⇒
          markInputs(read.inputs.toArray)
        case Read(input) ⇒
          require(inputMapping.contains(input.portIndex), s"Unknown input handle $input")
          require(!inputBunch.isCancelled(input.portIndex), s"Read not allowed from cancelled $input")
          require(!inputBunch.isDepleted(input.portIndex), s"Read not allowed from depleted $input")
          inputBunch.unmarkAllInputs()
          inputBunch.markInput(input.portIndex)
      }
    }

  changeBehavior(mergeLogic.initialState)
  changeCompletionHandling(mergeLogic.initialCompletionHandling)

  nextPhase(TransferPhase(precondition) { () ⇒
    behavior.condition match {
      case read: ReadAny ⇒
        val id = inputBunch.idToDequeue()
        val elem = inputBunch.dequeueAndYield(id)
        val inputHandle = inputMapping(id)
        changeBehavior(behavior.onInput(ctx, inputHandle, elem))
        triggerCompletionAfterRead(inputHandle)
      case read: ReadPreferred ⇒
        val id = inputBunch.idToDequeue()
        val elem = inputBunch.dequeueAndPrefer(id)
        val inputHandle = inputMapping(id)
        changeBehavior(behavior.onInput(ctx, inputHandle, elem))
        triggerCompletionAfterRead(inputHandle)
      case Read(inputHandle) ⇒
        val elem = inputBunch.dequeue(inputHandle.portIndex)
        changeBehavior(behavior.onInput(ctx, inputHandle, elem))
        triggerCompletionAfterRead(inputHandle)
      case read: ReadAll ⇒
        val inputHandles = read.inputs

        val values = inputHandles.collect {
          case input if include(input.portIndex) ⇒ input → inputBunch.dequeue(input.portIndex)
        }

        changeBehavior(behavior.onInput(ctx, inputHandles.head, read.mkResult(Map(values: _*))))

        // must be triggered after emiting the accumulated out value
        triggerCompletionAfterRead(inputHandles)
    }

  })

  private def triggerCompletionAfterRead(inputs: Seq[InputHandle]): Unit = {
    var j = 0
    while (j < inputs.length) {
      triggerCompletionAfterRead(inputs(j))
      j += 1
    }
  }

  private def triggerCompletionAfterRead(inputHandle: InputHandle): Unit =
    if (inputBunch.isDepleted(inputHandle.portIndex))
      triggerCompletion(inputHandle)

  private def triggerCompletion(inputHandle: InputHandle) {
    changeBehavior(completion.onComplete(ctx, inputHandle))
  }
}
