/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream

import akka.stream.impl.{ GraphStageTag, IslandTag, TraversalBuilder }

import scala.annotation.tailrec
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

  @deprecated("Use addAttributes instead of withAttributes, will be made internal", "2.5.8")
  def withAttributes(attr: Attributes): Graph[S, M]

  /**
   * Add a name for this graph, if this node or composed node already has a name it will be replaced.
   */
  def named(name: String): Graph[S, M] = {
    traversalBuilder.attributes.get[Attributes.Name] match {
      case Some(previous) if previous.n != name ⇒
        @tailrec
        def replaceFirstName(attrs: List[Attributes.Attribute], acc: List[Attributes.Attribute]): List[Attributes.Attribute] =
          attrs match {
            case (head: Attributes.Name) :: tail ⇒ acc.reverse ::: Attributes.Name(name) :: tail
            case head :: tail                    ⇒ replaceFirstName(tail, head :: acc)
            case Nil                             ⇒ throw new IllegalStateException("Never found Name attribute, something is seriously wrong.")
          }

        // to make names form a path through the graph, we need to avoid having multiple names
        // added on the same "level" of the graph
        withAttributes(Attributes(replaceFirstName(traversalBuilder.attributes.attributeList, Nil)))
      case Some(_) ⇒ this // same name added twice
      case _       ⇒ addAttributes(Attributes.name(name))
    }
  }

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
    addAttributes(
      Attributes.asyncBoundary and ActorAttributes.dispatcher(dispatcher)
    )

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  def async(dispatcher: String, inputBufferSize: Int) =
    addAttributes(
      Attributes.asyncBoundary and ActorAttributes.dispatcher(dispatcher)
        and Attributes.inputBuffer(inputBufferSize, inputBufferSize)
    )

  /**
   * Add the given attributes to this [[Graph]]. If the specific attribute was already present
   * on this graph this means the added attribute will be more specific than the existing one.
   * If this Source is a composite of multiple graphs, new attributes on the composite will be
   * less specific than attributes set directly on the individual graphs of the composite.
   */
  def addAttributes(attr: Attributes): Graph[S, M] = withAttributes(traversalBuilder.attributes and attr)
}
