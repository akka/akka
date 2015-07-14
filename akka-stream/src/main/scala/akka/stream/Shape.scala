/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.util.Collections.EmptyImmutableSeq

import scala.collection.immutable
import scala.collection.JavaConverters._

/**
 * An input port of a StreamLayout.Module. This type logically belongs
 * into the impl package but must live here due to how `sealed` works.
 * It is also used in the Java DSL for “untyped Inlets” as a work-around
 * for otherwise unreasonable existential types.
 */
sealed abstract class InPort { self: Inlet[_] ⇒
  final override def hashCode: Int = System.identityHashCode(this)
  final override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
}
/**
 * An output port of a StreamLayout.Module. This type logically belongs
 * into the impl package but must live here due to how `sealed` works.
 * It is also used in the Java DSL for “untyped Outlets” as a work-around
 * for otherwise unreasonable existential types.
 */
sealed abstract class OutPort { self: Outlet[_] ⇒
  final override def hashCode: Int = System.identityHashCode(this)
  final override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
}

/**
 * An Inlet is a typed input to a Shape. Its partner in the Module view
 * is the InPort (which does not bear an element type because Modules only
 * express the internal structural hierarchy of stream topologies).
 */
object Inlet {
  def apply[T](toString: String): Inlet[T] = new Inlet[T](toString)
}

final class Inlet[-T] private (override val toString: String) extends InPort {
  def carbonCopy(): Inlet[T] = Inlet(toString)
}

/**
 * An Outlet is a typed output to a Shape. Its partner in the Module view
 * is the OutPort (which does not bear an element type because Modules only
 * express the internal structural hierarchy of stream topologies).
 */
object Outlet {
  def apply[T](toString: String): Outlet[T] = new Outlet[T](toString)
}

final class Outlet[+T] private (override val toString: String) extends OutPort {
  def carbonCopy(): Outlet[T] = Outlet(toString)
}

/**
 * A Shape describes the inlets and outlets of a [[Graph]]. In keeping with the
 * philosophy that a Graph is a freely reusable blueprint, everything that
 * matters from the outside are the connections that can be made with it,
 * otherwise it is just a black box.
 */
abstract class Shape {
  /**
   * Scala API: get a list of all input ports
   */
  def inlets: immutable.Seq[Inlet[_]]

  /**
   * Scala API: get a list of all output ports
   */
  def outlets: immutable.Seq[Outlet[_]]

  /**
   * Create a copy of this Shape object, returning the same type as the
   * original; this constraint can unfortunately not be expressed in the
   * type system.
   */
  def deepCopy(): Shape

  /**
   * Create a copy of this Shape object, returning the same type as the
   * original but containing the ports given within the passed-in Shape.
   */
  def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape

  /**
   * Java API: get a list of all input ports
   */
  def getInlets: java.util.List[Inlet[_]] = inlets.asJava

  /**
   * Java API: get a list of all output ports
   */
  def getOutlets: java.util.List[Outlet[_]] = outlets.asJava

  /**
   * Compare this to another shape and determine whether the set of ports is the same (ignoring their ordering).
   */
  def hasSamePortsAs(s: Shape): Boolean =
    inlets.toSet == s.inlets.toSet && outlets.toSet == s.outlets.toSet

  /**
   * Compare this to another shape and determine whether the arrangement of ports is the same (including their ordering).
   */
  def hasSamePortsAndShapeAs(s: Shape): Boolean =
    inlets == s.inlets && outlets == s.outlets

  /**
   * Asserting version of [[#hasSamePortsAs]].
   */
  def requireSamePortsAs(s: Shape): Unit = require(hasSamePortsAs(s), nonCorrespondingMessage(s))

  /**
   * Asserting version of [[#hasSamePortsAndShapeAs]].
   */
  def requireSamePortsAndShapeAs(s: Shape): Unit = require(hasSamePortsAndShapeAs(s), nonCorrespondingMessage(s))

  private def nonCorrespondingMessage(s: Shape) =
    s"The inlets [${s.inlets.mkString(", ")}] and outlets [${s.outlets.mkString(", ")}] must correspond to the inlets [${inlets.mkString(", ")}] and outlets [${outlets.mkString(", ")}]"
}

/**
 * Java API for creating custom [[Shape]] types.
 */
abstract class AbstractShape extends Shape {
  /**
   * Provide the list of all input ports of this shape.
   */
  def allInlets: java.util.List[Inlet[_]]
  /**
   * Provide the list of all output ports of this shape.
   */
  def allOutlets: java.util.List[Outlet[_]]

  final override lazy val inlets: immutable.Seq[Inlet[_]] = allInlets.asScala.toList
  final override lazy val outlets: immutable.Seq[Outlet[_]] = allOutlets.asScala.toList

  final override def getInlets = allInlets
  final override def getOutlets = allOutlets
}

/**
 * This [[Shape]] is used for graphs that have neither open inputs nor open
 * outputs. Only such a [[Graph]] can be materialized by a [[Materializer]].
 */
sealed abstract class ClosedShape extends Shape
object ClosedShape extends ClosedShape {
  override val inlets: immutable.Seq[Inlet[_]] = EmptyImmutableSeq
  override val outlets: immutable.Seq[Outlet[_]] = EmptyImmutableSeq
  override def deepCopy() = this
  override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape = {
    require(inlets.isEmpty, s"proposed inlets [${inlets.mkString(", ")}] do not fit ClosedShape")
    require(outlets.isEmpty, s"proposed outlets [${outlets.mkString(", ")}] do not fit ClosedShape")
    this
  }

  /**
   * Java API: obtain ClosedShape instance
   */
  def getInstance: Shape = this
}

/**
 * This type of [[Shape]] can express any number of inputs and outputs at the
 * expense of forgetting about their specific types. It is used mainly in the
 * implementation of the [[Graph]] builders and typically replaced by a more
 * meaningful type of Shape when the building is finished.
 */
case class AmorphousShape(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]) extends Shape {
  override def deepCopy() = AmorphousShape(inlets.map(_.carbonCopy()), outlets.map(_.carbonCopy()))
  override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape = AmorphousShape(inlets, outlets)
}

/**
 * A Source [[Shape]] has exactly one output and no inputs, it models a source
 * of data.
 */
final case class SourceShape[+T](outlet: Outlet[T]) extends Shape {
  override val inlets: immutable.Seq[Inlet[_]] = EmptyImmutableSeq
  override val outlets: immutable.Seq[Outlet[_]] = List(outlet)

  override def deepCopy(): SourceShape[T] = SourceShape(outlet.carbonCopy())
  override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape = {
    require(inlets.isEmpty, s"proposed inlets [${inlets.mkString(", ")}] do not fit SourceShape")
    require(outlets.size == 1, s"proposed outlets [${outlets.mkString(", ")}] do not fit SourceShape")
    SourceShape(outlets.head)
  }
}

/**
 * A Flow [[Shape]] has exactly one input and one output, it looks from the
 * outside like a pipe (but it can be a complex topology of streams within of
 * course).
 */
final case class FlowShape[-I, +O](inlet: Inlet[I], outlet: Outlet[O]) extends Shape {
  override val inlets: immutable.Seq[Inlet[_]] = List(inlet)
  override val outlets: immutable.Seq[Outlet[_]] = List(outlet)

  override def deepCopy(): FlowShape[I, O] = FlowShape(inlet.carbonCopy(), outlet.carbonCopy())
  override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape = {
    require(inlets.size == 1, s"proposed inlets [${inlets.mkString(", ")}] do not fit FlowShape")
    require(outlets.size == 1, s"proposed outlets [${outlets.mkString(", ")}] do not fit FlowShape")
    FlowShape(inlets.head, outlets.head)
  }
}

/**
 * A Sink [[Shape]] has exactly one input and no outputs, it models a data sink.
 */
final case class SinkShape[-T](inlet: Inlet[T]) extends Shape {
  override val inlets: immutable.Seq[Inlet[_]] = List(inlet)
  override val outlets: immutable.Seq[Outlet[_]] = EmptyImmutableSeq

  override def deepCopy(): SinkShape[T] = SinkShape(inlet.carbonCopy())
  override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape = {
    require(inlets.size == 1, s"proposed inlets [${inlets.mkString(", ")}] do not fit SinkShape")
    require(outlets.isEmpty, s"proposed outlets [${outlets.mkString(", ")}] do not fit SinkShape")
    SinkShape(inlets.head)
  }
}

//#bidi-shape
/**
 * A bidirectional flow of elements that consequently has two inputs and two
 * outputs, arranged like this:
 *
 * {{{
 *        +------+
 *  In1 ~>|      |~> Out1
 *        | bidi |
 * Out2 <~|      |<~ In2
 *        +------+
 * }}}
 */
final case class BidiShape[-In1, +Out1, -In2, +Out2](in1: Inlet[In1],
                                                     out1: Outlet[Out1],
                                                     in2: Inlet[In2],
                                                     out2: Outlet[Out2]) extends Shape {
  //#implementation-details-elided
  override val inlets: immutable.Seq[Inlet[_]] = List(in1, in2)
  override val outlets: immutable.Seq[Outlet[_]] = List(out1, out2)

  /**
   * Java API for creating from a pair of unidirectional flows.
   */
  def this(top: FlowShape[In1, Out1], bottom: FlowShape[In2, Out2]) = this(top.inlet, top.outlet, bottom.inlet, bottom.outlet)

  override def deepCopy(): BidiShape[In1, Out1, In2, Out2] =
    BidiShape(in1.carbonCopy(), out1.carbonCopy(), in2.carbonCopy(), out2.carbonCopy())
  override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape = {
    require(inlets.size == 2, s"proposed inlets [${inlets.mkString(", ")}] do not fit BidiShape")
    require(outlets.size == 2, s"proposed outlets [${outlets.mkString(", ")}] do not fit BidiShape")
    BidiShape(inlets(0), outlets(0), inlets(1), outlets(1))
  }
  def reversed: Shape = copyFromPorts(inlets.reverse, outlets.reverse)
  //#implementation-details-elided
}
//#bidi-shape

object BidiShape {
  def apply[I1, O1, I2, O2](top: FlowShape[I1, O1], bottom: FlowShape[I2, O2]): BidiShape[I1, O1, I2, O2] =
    BidiShape(top.inlet, top.outlet, bottom.inlet, bottom.outlet)
}
