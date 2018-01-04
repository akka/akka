/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.streamref

import akka.NotUsed
import akka.annotation.InternalApi
import akka.stream.{ SinkRef, javadsl }
import akka.stream.scaladsl.Sink

import scala.concurrent.Future
import scala.util.{ Failure, Success }

/**
 * INTERNAL API
 * Allows users to directly use the SinkRef, even though we do have to go through the Future in order to be able
 * to materialize it. Since we initialize the ref from within the GraphStageLogic. See [[SinkRefStageImpl]] for usage.
 */
@InternalApi
private[akka] final case class MaterializedSinkRef[In](futureSink: Future[SinkRefImpl[In]]) extends SinkRef[In] {

  override def sink: Sink[In, NotUsed] =
    futureSink.value match {

      case Some(Success(ready)) ⇒
        // the normal case, since once materialization finishes, the future is guaranteed to have been completed
        ready.sink

      case Some(Failure(cause)) ⇒
        // materialization failed
        Sink.cancelled

      case None ⇒
        throw new Exception(s"This should not be possible! We guarantee to complete the materialized Future value when materialization finishes! Sink was: $futureSink")
      //        // not yet materialized -- in reality this case should not happen, since once materialization is finished, this Future is already completed
      //        // this impl is kept in case materialization semantics would change for some reason
      //        Source.fromFutureSource(futureSource.map(ref => ref.source)(ex)).mapMaterializedValue(_ ⇒ NotUsed)
    }

  override def getSink: javadsl.Sink[In, NotUsed] = sink.asJava

  override def toString: String =
    futureSink.value match {
      case None                 ⇒ s"SinkRef(<materializing-source-ref>)"
      case Some(Success(ready)) ⇒ ready.toString
      case Some(Failure(ex))    ⇒ s"SinkRef(<failed:${ex.getMessage}>)"
    }

}
