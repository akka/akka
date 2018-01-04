/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.streamref

import akka.NotUsed
import akka.annotation.InternalApi
import akka.stream.scaladsl.Source
import akka.stream.{ SourceRef, javadsl }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

/**
 * INTERNAL API
 * Allows users to directly use the SourceRef, even though we do have to go through the Future in order to be able
 * to materialize it. Since we initialize the ref from within the GraphStageLogic. See [[SourceRefStageImpl]] for usage.
 */
@InternalApi
private[akka] final case class MaterializedSourceRef[Out](futureSource: Future[SourceRefImpl[Out]]) extends SourceRef[Out] {

  override def source: Source[Out, NotUsed] =
    futureSource.value match {

      case Some(Success(ready)) ⇒
        // the normal case, since once materialization finishes, the future is guaranteed to have been completed
        ready.source

      case Some(Failure(cause)) ⇒
        // materialization failed
        Source.failed(cause).named("SourceRef")

      case None ⇒
        throw new Exception(s"This should not be possible! We guarantee to complete the materialized Future value when materialization finishes! Source was: $futureSource")
      //        // not yet materialized -- in reality this case should not happen, since once materialization is finished, this Future is already completed
      //        // this impl is kept in case materialization semantics would change for some reason
      //        Source.fromFutureSource(futureSource.map(ref => ref.source)(ex)).mapMaterializedValue(_ ⇒ NotUsed)
    }

  override def getSource: javadsl.Source[Out, NotUsed] = source.asJava

  override def toString: String =
    futureSource.value match {
      case None                 ⇒ s"SourceRef(<materializing-source-ref>)"
      case Some(Success(ready)) ⇒ ready.toString
      case Some(Failure(ex))    ⇒ s"SourceRef(<failed:${ex.getMessage}>)"
    }

}
