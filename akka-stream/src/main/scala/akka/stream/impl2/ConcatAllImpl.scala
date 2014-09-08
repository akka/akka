/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import akka.stream.MaterializerSettings
import org.reactivestreams.Publisher
import akka.stream.impl.MultiStreamInputProcessor.SubstreamKey
import akka.stream.impl.TransferPhase
import akka.stream.impl.MultiStreamInputProcessor
import akka.stream.scaladsl2.FlowWithSource
import akka.stream.scaladsl2.FlowMaterializer

/**
 * INTERNAL API
 */
private[akka] class ConcatAllImpl(materializer: FlowMaterializer)
  extends MultiStreamInputProcessor(materializer.settings) {

  val takeNextSubstream = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val flow = primaryInputs.dequeueInputElement().asInstanceOf[FlowWithSource[Any, Any]]
    val publisher = flow.toPublisher()(materializer)
    // FIXME we can pass the flow to createSubstreamInputs (but avoiding copy impl now)
    val inputs = createSubstreamInputs(publisher)
    nextPhase(streamSubstream(inputs))
  }

  def streamSubstream(substream: SubstreamInputs): TransferPhase =
    TransferPhase(substream.NeedsInputOrComplete && primaryOutputs.NeedsDemand) { () ⇒
      if (substream.inputsDepleted) nextPhase(takeNextSubstream)
      else primaryOutputs.enqueueOutputElement(substream.dequeueInputElement())
    }

  nextPhase(takeNextSubstream)

  override def invalidateSubstream(substream: SubstreamKey, e: Throwable): Unit = fail(e)
}
