/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream._
import akka.stream.scaladsl._
import language.higherKinds

object SubFlowImpl {
  trait MergeBack[In, F[+_]] {
    def apply[T](f: Flow[In, T, Unit], breadth: Int): F[T]
  }
}

class SubFlowImpl[In, Out, Mat, F[+_], C](val subFlow: Flow[In, Out, Unit],
                                          mergeBackFunction: SubFlowImpl.MergeBack[In, F],
                                          finishFunction: Sink[In, Unit] ⇒ C)
  extends SubFlow[Out, Mat, F, C] {

  override def deprecatedAndThen[U](op: Stages.StageModule): SubFlow[U, Mat, F, C] =
    new SubFlowImpl[In, U, Mat, F, C](subFlow.deprecatedAndThen(op), mergeBackFunction, finishFunction)

  override def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T] =
    new SubFlowImpl[In, T, Mat, F, C](subFlow.via(flow), mergeBackFunction, finishFunction)

  override def withAttributes(attr: Attributes): SubFlow[Out, Mat, F, C] =
    new SubFlowImpl[In, Out, Mat, F, C](subFlow.withAttributes(attr), mergeBackFunction, finishFunction)

  override def addAttributes(attr: Attributes): SubFlow[Out, Mat, F, C] =
    new SubFlowImpl[In, Out, Mat, F, C](subFlow.addAttributes(attr), mergeBackFunction, finishFunction)

  override def named(name: String): SubFlow[Out, Mat, F, C] =
    new SubFlowImpl[In, Out, Mat, F, C](subFlow.named(name), mergeBackFunction, finishFunction)

  override def mergeSubstreamsWithParallelism(breadth: Int): F[Out] = mergeBackFunction(subFlow, breadth)

  def to[M](sink: Graph[SinkShape[Out], M]): C = finishFunction(subFlow.to(sink))
}
