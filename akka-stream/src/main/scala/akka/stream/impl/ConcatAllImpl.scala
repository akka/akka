/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Source, Sink }
import akka.actor.{ Deploy, Props }

/**
 * INTERNAL API
 */
private[akka] object ConcatAllImpl {
  def props(f: Any ⇒ Source[Any, _], materializer: ActorMaterializer): Props =
    Props(new ConcatAllImpl(f, materializer)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] class ConcatAllImpl(f: Any ⇒ Source[Any, _], materializer: ActorMaterializer)
  extends MultiStreamInputProcessor(materializer.settings) {

  import akka.stream.impl.MultiStreamInputProcessor._

  val takeNextSubstream = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val publisher = f(primaryInputs.dequeueInputElement()).runWith(Sink.publisher(1))(materializer)
    // FIXME we can pass the flow to createSubstreamInput (but avoiding copy impl now)
    val inputs = createAndSubscribeSubstreamInput(publisher)
    nextPhase(streamSubstream(inputs))
  }

  def streamSubstream(substream: SubstreamInput): TransferPhase =
    TransferPhase(substream.NeedsInputOrComplete && primaryOutputs.NeedsDemand) { () ⇒
      if (substream.inputsDepleted) nextPhase(takeNextSubstream)
      else primaryOutputs.enqueueOutputElement(substream.dequeueInputElement())
    }

  initialPhase(1, takeNextSubstream)

  override def invalidateSubstreamInput(substream: SubstreamKey, e: Throwable): Unit = fail(e)

}
