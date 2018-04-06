/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import akka.stream.UniformFanInShape
import akka.stream.scaladsl.MergeLatest
import akka.stream.stage.GraphStage

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * MergeLatest joins elements from N input streams into stream of lists of size N.
 * i-th element in list is the latest emitted element from i-th input stream.
 * MergeLatest emits list for each element emitted from some input stream,
 * but only after each stream emitted at least one element
 *
 * '''Emits when''' element is available from some input and each input emits at least one element from stream start
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete (eagerClose=false) or one upstream completes (eagerClose=true)
 *
 * '''Cancels when''' downstream cancels
 *
 */
object MergeLatest {
  /**
   * Create a new `MergeLatest` with the specified number of input ports.
   *
   * @param inputPorts number of input ports
   * @param eagerComplete if true, the merge latest will complete as soon as one of its inputs completes.
   */
  def apply[T: ClassTag](inputPorts: Int, eagerComplete: Boolean = false): GraphStage[UniformFanInShape[T, java.util.List[T]]] =
    new MergeLatest[T, java.util.List[T]](inputPorts, eagerComplete)(x ⇒ x.toList.asJava)

}

