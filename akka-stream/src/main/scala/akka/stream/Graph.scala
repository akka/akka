/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import akka.stream.impl.StreamLayout
import scala.annotation.unchecked.uncheckedVariance

/**
 * @groupname graph
 * @groupprio graph 70
 */
trait Graph[+S <: Shape, +M] {
  /**
   * Type-level accessor for the shape parameter of this graph.
   */
  type Shape = S @uncheckedVariance
  /**
   * The shape of a graph is all that is externally visible: its inlets and outlets.
   *
   * @group graph
   */
  def shape: S
  /**
   * INTERNAL API.
   *
   * Every materializable element must be backed by a stream layout module
   *
   * @group graph
   */
  private[stream] def module: StreamLayout.Module

  /**
   *
   * @group graph
   */
  def withAttributes(attr: Attributes): Graph[S, M]
  /**
   *
   * @group graph
   */
  def named(name: String): Graph[S, M] = withAttributes(Attributes.name(name))

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @group graph
   */
  def async: Graph[S, M] = addAttributes(Attributes.asyncBoundary)
  /**
   *
   * @group graph
   */
  def addAttributes(attr: Attributes): Graph[S, M] = withAttributes(module.attributes and attr)
}
