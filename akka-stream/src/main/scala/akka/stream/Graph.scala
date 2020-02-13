/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.annotation.InternalApi
import akka.stream.impl.TraversalBuilder
import akka.stream.scaladsl.GenericGraph

import scala.annotation.unchecked.uncheckedVariance

/**
 * Not intended to be directly extended by user classes
 *
 * @see [[akka.stream.stage.GraphStage]]
 */
trait Graph[+S <: Shape, +M] {

  /**
   * Type-level accessor for the shape parameter of this graph.
   */
  type Shape = S @uncheckedVariance
  /**
   * The shape of a graph is all that is externally visible: its inlets and outlets.
   */
  def shape: S

  /**
   * INTERNAL API.
   *
   * Every materializable element must be backed by a stream layout module
   */
  private[stream] def traversalBuilder: TraversalBuilder

  def withAttributes(attr: Attributes): Graph[S, M]

  def named(name: String): Graph[S, M] = addAttributes(Attributes.name(name))

  /**
   * Put an asynchronous boundary around this `Graph`
   */
  def async: Graph[S, M] = addAttributes(Attributes.asyncBoundary)

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  def async(dispatcher: String) =
    addAttributes(Attributes.asyncBoundary and ActorAttributes.dispatcher(dispatcher))

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  def async(dispatcher: String, inputBufferSize: Int) =
    addAttributes(
      Attributes.asyncBoundary and ActorAttributes.dispatcher(dispatcher)
      and Attributes.inputBuffer(inputBufferSize, inputBufferSize))

  /**
   * Add the given attributes to this [[Graph]]. If the specific attribute was already present
   * on this graph this means the added attribute will be more specific than the existing one.
   * If this Source is a composite of multiple graphs, new attributes on the composite will be
   * less specific than attributes set directly on the individual graphs of the composite.
   */
  def addAttributes(attr: Attributes): Graph[S, M] = withAttributes(traversalBuilder.attributes and attr)
}

object Graph {
  def mapMaterializedValue[S <: Shape, M1, M2](g: Graph[S, M1])(f: M1 => M2): Graph[S, M2] = {
    new GenericGraph(g.shape, g.traversalBuilder).mapMaterializedValue(f)
  }
  implicit class GraphMapMatVal[S <: Shape, M](self: Graph[S, M]) {
    def mapMaterializedValue[M2](f: M => M2): Graph[S, M2] = Graph.mapMaterializedValue(self)(f)
  }
}

/**
 * INTERNAL API
 *
 * Allows creating additional API on top of an existing Graph by extending from this class and
 * accessing the delegate
 */
@InternalApi
private[stream] abstract class GraphDelegate[+S <: Shape, +Mat](delegate: Graph[S, Mat]) extends Graph[S, Mat] {
  final override def shape: S = delegate.shape
  final override private[stream] def traversalBuilder: TraversalBuilder = delegate.traversalBuilder
}
