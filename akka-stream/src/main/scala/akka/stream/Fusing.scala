/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.{ util ⇒ ju }
import scala.collection.immutable
import scala.collection.JavaConverters._
import akka.stream.impl.StreamLayout._

/**
 * This class holds some graph transformation functions that can fuse together
 * multiple operation stages into synchronous execution islands. The purpose is
 * to reduce the number of Actors that are created in order to execute the stream
 * and thereby improve start-up cost as well as reduce element traversal latency
 * for large graphs. Fusing itself is a time-consuming operation, meaning that
 * usually it is best to cache the result of this computation and reuse it instead
 * of fusing the same graph many times.
 *
 * Fusing together all operations which allow this treatment will reduce the
 * parallelism that is available in the stream graph’s execution—in the worst case
 * it will become single-threaded and not benefit from multiple CPU cores at all.
 * Where parallelism is required, the [[akka.stream.Attributes#AsyncBoundary]]
 * attribute can be used to declare subgraph boundaries across which the graph
 * shall not be fused.
 */
object Fusing {

  /**
   * Fuse all operations where this is technically possible (i.e. all
   * implementations based on [[akka.stream.stage.GraphStage]]) and not forbidden
   * via [[akka.stream.Attributes#AsyncBoundary]].
   */
  def aggressive[S <: Shape, M](g: Graph[S, M]): FusedGraph[S, M] =
    akka.stream.impl.fusing.Fusing.aggressive(g)

  /**
   * A fused graph of the right shape, containing a [[FusedModule]] which
   * holds more information on the operation structure of the contained stream
   * topology for convenient graph traversal.
   */
  case class FusedGraph[S <: Shape, M](override val module: FusedModule,
                                       override val shape: S) extends Graph[S, M] {
    override def withAttributes(attr: Attributes) = copy(module = module.withAttributes(attr))
  }

  /**
   * When fusing a [[Graph]] a part of the internal stage wirings are hidden within
   * [[akka.stream.impl.fusing.GraphInterpreter#GraphAssembly]] objects that are
   * optimized for high-speed execution. This structural information bundle contains
   * the wirings in a more accessible form, allowing traversal from port to upstream
   * or downstream port and from there to the owning module (or graph vertex).
   */
  final case class StructuralInfo(upstreams: immutable.Map[InPort, OutPort],
                                  downstreams: immutable.Map[OutPort, InPort],
                                  inOwners: immutable.Map[InPort, Module],
                                  outOwners: immutable.Map[OutPort, Module])

}
