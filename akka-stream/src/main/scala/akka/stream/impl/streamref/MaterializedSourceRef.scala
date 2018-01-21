///**
// * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
// */
//package akka.stream.impl.streamref
//
//import java.util.concurrent.atomic.AtomicReference
//
//import akka.NotUsed
//import akka.actor.ActorRef
//import akka.annotation.InternalApi
//import akka.stream.scaladsl.Source
//import akka.stream.{ OverflowStrategy, SourceRef, javadsl }
//
//import scala.concurrent.{ ExecutionContext, Future }
//import scala.util.{ Failure, Success }
//
///**
// * INTERNAL API
// * Allows users to directly use the SourceRef, even though we do have to go through the Future in order to be able
// * to materialize it. Since we initialize the ref from within the GraphStageLogic. See [[SourceRefStageImpl]] for usage.
// */
//@InternalApi
//private[akka] final case class MaterializedSourceRef[Out](futureSource: Future[SourceRefImpl[Out]]) extends SourceRef[Out] {
//
//  class State
//  final case class Initializing(buffer: Vector[Out]) extends State
//  final case class Initialized(ref: SourceRefImpl[Out]) extends State
//
//  val it = new AtomicReference[State](Initializing(Vector.empty))
//
//  // the advanced logic here is in order to allow RUNNING a stream locally ASAP even while materialization is still in-flight (preStart has not completed futureSource)
//  override def source: Source[Out, NotUsed] =
//    futureSource.value match {
//
//      case Some(Success(ready)) ⇒
//        // the normal case, since once materialization finishes, the future is guaranteed to have been completed
//        ready.source
//
//      case Some(Failure(cause)) ⇒
//        // materialization failed
//        Source.failed(cause).named("SourceRef")
//
//      case None ⇒
//        // not yet materialized -- in reality this case should not happen, since once materialization is finished, this Future is already completed
//        // this impl is kept in case materialization semantics would change for some reason
//        Source.fromFutureSource(futureSource.map(ref ⇒ ref.source)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)).mapMaterializedValue(_ ⇒ NotUsed)
//    }
//
//  //  def forSerializationRef: SourceRef[Out] =
//  //    futureSource.value match {
//  //      case Some(Success(ready)) ⇒
//  //        // the normal case, since once materialization finishes, the future is guaranteed to have been completed
//  //        ready
//  //
//  //      case Some(Failure(cause)) ⇒
//  //        throw new IllegalStateException("Illegal serialization attempt, this stream has never materialized the SourceRef!", cause)
//  //
//  //      case None ⇒
//  //        // preStart has not finished yet, so we need to create and serialize a proxy ref
//  //        val proxy = mkProxy()
//  //
//  //        new SourceRef[Out] {
//  //          override def source: Source[Out, NotUsed] =
//  //            ???
//  //        }
//  //    }
//
//  override def toString: String =
//    futureSource.value match {
//      case None                 ⇒ s"SourceRef(<materializing-source-ref>)"
//      case Some(Success(ready)) ⇒ ready.toString
//      case Some(Failure(ex))    ⇒ s"SourceRef(<failed:${ex.getMessage}>)"
//    }
//
//}
//
//case class BufferedRef() {
//
//}
