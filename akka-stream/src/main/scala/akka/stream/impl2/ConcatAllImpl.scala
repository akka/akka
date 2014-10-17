/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import akka.stream.impl.{ Extract, MultiStreamInputProcessor, TransferPhase }
import akka.stream.scaladsl2.{ Sink, FlowMaterializer }

/**
 * INTERNAL API
 */
private[akka] class ConcatAllImpl(materializer: FlowMaterializer)
  extends MultiStreamInputProcessor(materializer.settings) {

  import akka.stream.impl.MultiStreamInputProcessor._

  val takeNextSubstream = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val Extract.Source(source) = primaryInputs.dequeueInputElement()
    val publisher = source.runWith(Sink.publisher)(materializer)
    // FIXME we can pass the flow to createSubstreamInput (but avoiding copy impl now)
    val inputs = createAndSubscribeSubstreamInput(publisher)
    nextPhase(streamSubstream(inputs))
  }

  def streamSubstream(substream: SubstreamInput): TransferPhase =
    TransferPhase(substream.NeedsInputOrComplete && primaryOutputs.NeedsDemand) { () ⇒
      if (substream.inputsDepleted) nextPhase(takeNextSubstream)
      else primaryOutputs.enqueueOutputElement(substream.dequeueInputElement())
    }

  nextPhase(takeNextSubstream)

  override def invalidateSubstreamInput(substream: SubstreamKey, e: Throwable): Unit = fail(e)
}
