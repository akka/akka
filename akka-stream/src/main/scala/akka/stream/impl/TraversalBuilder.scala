/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream._
import akka.stream.impl.StreamLayout.AtomicModule
import akka.stream.impl.fusing.GraphStageModule
import akka.stream.scaladsl.Keep
import akka.util.OptionVal
import scala.language.existentials

/**
 * Graphs to be materialized are defined by their traversal. There is no explicit graph information tracked, instead
 * a sequence of steps required to "reconstruct" the graph.
 *
 * "Reconstructing" a graph here has a very clear-cut definition: assign a gapless range of integers from
 * 0..connectionCount to inputs and outputs of modules, so that those that are wired together receive the same
 * number (and those which are not receive different numbers). This feature can be used to
 *  - materialize a graph, using the slots as indices to an array of Publishers/Subscribers that need to be wired
 *    together
 *  - fuse a graph, using the slots to construct a [[akka.stream.impl.fusing.GraphInterpreter.GraphAssembly]] which
 *    uses a similar layout
 *  - create a DOT formatted output for visualization
 *  - convert the graph to another data structure
 *
 * The Traversal is designed to be position independent so that multiple traversals can be composed relatively
 * simply. This particular feature also avoids issues with multiply imported modules where the identity must
 * be encoded somehow. The two imports don't need any special treatment as they are at different positions in
 * the traversal. See [[MaterializeAtomic]] for more details.
 */
sealed trait Traversal {

  /**
   * Concatenates two traversals building a new Traversal which traverses both.
   */
  def concat(that: Traversal): Traversal = {
    Concat.normalizeConcat(this, that)
  }

  def rewireFirstTo(relativeOffset: Int): Traversal = null
}

object Concat {

  def normalizeConcat(first: Traversal, second: Traversal): Traversal = {
    if (second eq EmptyTraversal) first
    else if (first eq PushNotUsed) {
      // No need to push NotUsed and Pop it immediately
      second match {
        case Pop               ⇒ EmptyTraversal
        case Concat(Pop, rest) ⇒ rest
        case _                 ⇒ Concat(PushNotUsed, second)
      }
    } else {
      // Limit the tree by rotations
      first match {
        case Concat(firstfirst, firstsecond) ⇒
          // Note that we DON'T use firstfirst.concat(firstsecond.concat(second)) here,
          // although that would fully linearize the tree.
          // The reason is to simply avoid going n^2. The rotation below is of constant time and good enough.
          Concat(firstfirst, Concat(firstsecond, second))
        case _ ⇒ Concat(first, second)
      }
    }
  }

}

/**
 * A Traversal that consists of two traversals. The linked traversals must be traversed in first, next order.
 */
final case class Concat(first: Traversal, next: Traversal) extends Traversal {
  override def rewireFirstTo(relativeOffset: Int): Traversal = {
    val firstResult = first.rewireFirstTo(relativeOffset)
    if (firstResult ne null)
      firstResult.concat(next)
    else
      first.concat(next.rewireFirstTo(relativeOffset))

  }
}

/**
 * Arriving at this step means that an atomic module needs to be materialized (or any other activity which
 * assigns something to wired output-input port pairs).
 *
 * The traversing party must assign port numbers in the following way:
 *  - input ports are implicitly assigned to numbers. Every module's input ports are assigned to consecutive numbers
 *    according to their order in the shape. In other words, the materializer only needs to keep a counter and
 *    increment it for every visited input port.
 *  - the assigned number of the first input port for every module should be saved while materializing the module.
 *    every output port should be assigned to (base + outToSlots(out.id)) where base is the number of the first
 *    input port of the module (or the last unused input number if it has no input ports) and outToSlots is the
 *    array provided by the traversal step.
 *
 * Since the above two rules always require local only computations (except a counter) this achieves
 * positional independence of materializations.
 *
 * See the `TraversalTestUtils` class and the `testMaterialize` method for a simple example.
 */
final case class MaterializeAtomic(module: AtomicModule[Shape, Any], outToSlots: Array[Int]) extends Traversal {
  override def toString: String = s"MaterializeAtomic($module, ${outToSlots.mkString("[", ", ", "]")})"

  override def rewireFirstTo(relativeOffset: Int): Traversal = copy(outToSlots = Array(relativeOffset))
}

/**
 * Traversal with no steps.
 */
object EmptyTraversal extends Traversal {
  override def concat(that: Traversal): Traversal = that
}

sealed trait MaterializedValueOp extends Traversal

case object Pop extends MaterializedValueOp
case object PushNotUsed extends MaterializedValueOp
final case class Transform(mapper: Any ⇒ Any) extends MaterializedValueOp
final case class Compose(composer: (Any, Any) ⇒ Any) extends MaterializedValueOp

final case class PushAttributes(attributes: Attributes) extends Traversal
final case object PopAttributes extends Traversal

final case class EnterIsland(islandTag: IslandTag) extends Traversal
final case object ExitIsland extends Traversal

object TraversalBuilder {

  private val cachedEmptyCompleted = CompletedTraversalBuilder(PushNotUsed, 0, Map.empty, Attributes.none)

  /**
   * Assign ports their id, which is their position inside the Shape. This is used both by the GraphInterpreter
   * and the layout system here.
   */
  private[impl] def initShape(shape: Shape): Unit = {
    // Initialize port IDs
    val inIter = shape.inlets.iterator
    var i = 0
    while (inIter.hasNext) {
      inIter.next.id = i
      i += 1
    }

    val outIter = shape.outlets.iterator
    i = 0
    while (outIter.hasNext) {
      outIter.next.id = i
      i += 1
    }
  }

  def empty(attributes: Attributes = Attributes.none): TraversalBuilder = {
    if (attributes eq Attributes.none) cachedEmptyCompleted
    else CompletedTraversalBuilder(PushNotUsed, 0, Map.empty, attributes)
  }

  /**
   * Create a generic traversal builder starting from an atomic module.
   */
  def atomic(module: AtomicModule[Shape, Any], attributes: Attributes): TraversalBuilder = {
    initShape(module.shape)

    val builder =
      if (module.shape.outlets.isEmpty) {
        val b = CompletedTraversalBuilder(
          traversalSoFar = MaterializeAtomic(module, Array.ofDim[Int](module.shape.outlets.size)),
          inSlots = module.shape.inlets.size,
          inToOffset = module.shape.inlets.map(in ⇒ in → in.id).toMap,
          Attributes.none)
        b
      } else {
        AtomicTraversalBuilder(
          module,
          Array.ofDim[Int](module.shape.outlets.size),
          module.shape.outlets.size,
          Attributes.none)
      }
    // important to use setAttributes because it will create island for async (dispatcher attribute)
    builder.setAttributes(attributes)
  }

  def printTraversal(t: Traversal, indent: Int = 0): Unit = {
    var current: Traversal = t
    var slot = 0

    def prindent(s: String): Unit = println(" | " * indent + s)

    while (current != EmptyTraversal) {
      var nextStep: Traversal = EmptyTraversal

      current match {
        case PushNotUsed                        ⇒ prindent("push NotUsed")
        case Pop                                ⇒ prindent("pop mat")
        case _: Transform                       ⇒ prindent("transform mat")
        case _: Compose                         ⇒ prindent("compose mat")
        case PushAttributes(attr)               ⇒ prindent("push attr " + attr)
        case PopAttributes                      ⇒ prindent("pop attr")
        case EnterIsland(tag)                   ⇒ prindent("enter island " + tag)
        case ExitIsland                         ⇒ prindent("exit island")
        case MaterializeAtomic(mod, outToSlots) ⇒ prindent("materialize " + mod + " " + outToSlots.mkString("[", ", ", "]"))
        case Concat(first, next) ⇒
          printTraversal(first, indent + 1)
          nextStep = next
        case _ ⇒
      }

      current = nextStep
    }
  }

  def printWiring(t: Traversal, baseSlot: Int = 0): Int = {
    var current: Traversal = t
    var slot = baseSlot

    while (current != EmptyTraversal) {
      var nextStep: Traversal = EmptyTraversal

      current match {
        case MaterializeAtomic(mod, outToSlots) ⇒
          println(s"materialize $mod")
          val base = slot
          mod.shape.inlets.foreach { in ⇒
            println(s"  wiring $in to $slot")
            slot += 1
          }
          mod.shape.outlets.foreach { out ⇒
            println(s"  wiring $out to ${base + outToSlots(out.id)}")
          }
        case Concat(first, next) ⇒
          slot = printWiring(first, slot)
          nextStep = next
        case _ ⇒
      }

      current = nextStep
    }
    slot
  }
}

/**
 * A builder for a Traversal. The purpose of subclasses of this trait is to eventually build a Traversal that
 * describes the graph. Depending on whether the graph is linear or generic different approaches can be used but
 * they still result in a Traversal.
 *
 * The resulting Traversal can be accessed via the `traversal` method once the graph is completed (all ports are
 * wired). The Traversal may be accessed earlier, depending on the type of the builder and certain conditions.
 * See [[CompositeTraversalBuilder]] and [[LinearTraversalBuilder]].
 */
sealed trait TraversalBuilder {

  /**
   * Adds a module to the builder. It is possible to add a module with a different Shape (import), in this
   * case the ports of the shape MUST have their `mappedTo` field pointing to the original ports. The act of being
   * imported will not be reflected in the final Traversal, the Shape is only used by the builder to disambiguate
   * between multiple imported instances of the same module.
   *
   * See append in the [[LinearTraversalBuilder]] for a more efficient alternative for linear graphs.
   */
  def add[A, B, C](submodule: TraversalBuilder, shape: Shape, combineMat: (A, B) ⇒ C): TraversalBuilder

  /**
   * Maps the materialized value produced by the module built-up so far with the provided function, providing a new
   * TraversalBuilder returning the mapped materialized value.
   */
  def transformMat[A, B](f: A ⇒ B): TraversalBuilder

  protected def internalSetAttributes(attributes: Attributes): TraversalBuilder

  def setAttributes(attributes: Attributes): TraversalBuilder = {
    if (attributes.isAsync) this.makeIsland(GraphStageTag).internalSetAttributes(attributes)
    else internalSetAttributes(attributes)
  }

  def attributes: Attributes

  /**
   * Connects two unwired ports in the graph. For imported modules, use the ports of their "import shape". These
   * ports must have their `mappedTo` field set and point to the original ports.
   *
   * See append in the [[LinearTraversalBuilder]] for a more efficient alternative for linear graphs.
   */
  def wire(out: OutPort, in: InPort): TraversalBuilder

  /**
   * Returns the base offset (the first number an input port would receive if there is any) of the module to which
   * the port belongs *relative to this builder*. This is used to calculate the relative offset of output port mappings
   * (see [[MaterializeAtomic]]).
   *
   * This method only guarantees to return the offset of modules for output ports that have not been wired.
   */
  def offsetOfModule(out: OutPort): Int

  /**
   * Returns whether the given output port has been wired in the graph or not.
   */
  def isUnwired(out: OutPort): Boolean

  /**
   * Returns whether the given output port has been wired in the graph or not.
   */
  def isUnwired(in: InPort): Boolean

  /**
   * Returns the number assigned to a certain input port *relative* to this module.
   *
   * This method only guarantees to return the offset of input ports that have not been wired.
   */
  def offsetOf(in: InPort): Int

  /**
   * Finish the wiring of an output port to an input port by assigning the relative slot for the output port.
   *
   * (see [[MaterializeAtomic]] for details of the resolution process)
   */
  def assign(out: OutPort, relativeSlot: Int): TraversalBuilder

  /**
   * Returns true if the Traversal is available. Not all builders are able to build up the Traversal incrementally.
   * Generally a traversal is complete if there are no unwired output ports.
   */
  def isTraversalComplete: Boolean

  /**
   * The total number of input ports encountered so far. Gives the first slot to which a new input port can be
   * assigned (if a new module is added).
   */
  def inSlots: Int

  /**
   * Returns the Traversal if ready for this (sub)graph.
   */
  def traversal: Traversal = throw new IllegalStateException("Traversal can be only acquired from a completed builder")

  /**
   * The number of output ports that have not been wired.
   */
  def unwiredOuts: Int

  /**
   * Wraps the builder in an island that can be materialized differently, using async boundaries to bridge
   * between islands.
   */
  def makeIsland(islandTag: IslandTag): TraversalBuilder
}

/**
 * Returned by [[CompositeTraversalBuilder]] once all output ports of a subgraph has been wired.
 */
final case class CompletedTraversalBuilder(
  traversalSoFar: Traversal,
  inSlots:        Int,
  inToOffset:     Map[InPort, Int],
  attributes:     Attributes,
  islandTag:      OptionVal[IslandTag] = OptionVal.None) extends TraversalBuilder {

  override def add[A, B, C](submodule: TraversalBuilder, shape: Shape, combineMat: (A, B) ⇒ C): TraversalBuilder = {
    val key = new BuilderKey
    CompositeTraversalBuilder(
      reverseBuildSteps = key :: Nil,
      inSlots = inSlots,
      inOffsets = inToOffset,
      pendingBuilders = Map(key → this),
      attributes = attributes).add(submodule, shape, combineMat)
  }

  override def traversal: Traversal = {
    val withIsland = islandTag match {
      case OptionVal.Some(tag) ⇒ EnterIsland(tag).concat(traversalSoFar).concat(ExitIsland)
      case _                   ⇒ traversalSoFar
    }

    if (attributes eq Attributes.none) withIsland
    else PushAttributes(attributes).concat(withIsland).concat(PopAttributes)
  }

  override def transformMat[A, B](f: (A) ⇒ B): TraversalBuilder =
    copy(traversalSoFar = traversalSoFar.concat(Transform(f.asInstanceOf[Any ⇒ Any])))

  override def offsetOf(in: InPort): Int = inToOffset(in)

  override def isTraversalComplete: Boolean = true

  override def wire(out: OutPort, in: InPort): TraversalBuilder =
    throw new UnsupportedOperationException(s"Cannot wire ports in a completed builder. ${out.mappedTo} ~> ${in.mappedTo}")

  override def internalSetAttributes(attributes: Attributes): TraversalBuilder =
    copy(attributes = attributes)

  override def unwiredOuts: Int = 0

  override def makeIsland(islandTag: IslandTag): TraversalBuilder =
    this.islandTag match {
      case OptionVal.None    ⇒ copy(islandTag = OptionVal(islandTag))
      case OptionVal.Some(_) ⇒ this
    }

  override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder =
    throw new UnsupportedOperationException("Cannot assign ports to slots in a completed builder.")

  override def offsetOfModule(out: OutPort): Int =
    throw new UnsupportedOperationException("Cannot look up offsets in a completed builder.")

  override def isUnwired(out: OutPort): Boolean = false
  override def isUnwired(in: InPort): Boolean = inToOffset.contains(in)
}

/**
 * Represents a builder that contains a single atomic module. Its primary purpose is to track and build the
 * outToSlot array which will be then embedded in a [[MaterializeAtomic]] Traversal step.
 */
final case class AtomicTraversalBuilder(
  module:      AtomicModule[Shape, Any],
  outToSlot:   Array[Int],
  unwiredOuts: Int,
  attributes:  Attributes) extends TraversalBuilder {

  override def add[A, B, C](submodule: TraversalBuilder, shape: Shape, combineMat: (A, B) ⇒ C): TraversalBuilder = {
    // TODO: Use automatically a linear builder if applicable
    // Create a composite, add ourselves, then the other.
    CompositeTraversalBuilder(attributes = attributes)
      .add(this, module.shape, Keep.right)
      .add(submodule, shape, combineMat)
  }

  override def transformMat[A, B](f: (A) ⇒ B): TraversalBuilder =
    TraversalBuilder.empty().add(this, module.shape, Keep.right).transformMat(f)

  override val inSlots: Int = module.shape.inlets.size

  override def offsetOfModule(out: OutPort): Int = 0
  override def offsetOf(in: InPort): Int = in.id
  override def isTraversalComplete: Boolean = false
  override def isUnwired(out: OutPort): Boolean = true
  override def isUnwired(in: InPort): Boolean = true

  override def wire(out: OutPort, in: InPort): TraversalBuilder = {
    assign(out, offsetOf(in) - offsetOfModule(out))
  }

  override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
    // Create a new array, with the output port assigned to its relative slot
    val newOutToSlot = java.util.Arrays.copyOf(outToSlot, outToSlot.length)
    newOutToSlot(out.id) = relativeSlot

    // Check if every output port has been assigned, if yes, we have a Traversal for this module.
    val newUnwiredOuts = unwiredOuts - 1
    if (newUnwiredOuts == 0) {
      CompletedTraversalBuilder(
        traversalSoFar = MaterializeAtomic(module, newOutToSlot),
        inSlots = inSlots,
        // TODO Optimize Map creation
        inToOffset = module.shape.inlets.iterator.map(in ⇒ in → in.id).toMap,
        attributes = attributes)
    } else copy(outToSlot = newOutToSlot, unwiredOuts = newUnwiredOuts)
  }

  override def internalSetAttributes(attributes: Attributes): TraversalBuilder =
    copy(attributes = attributes)

  override def makeIsland(islandTag: IslandTag): TraversalBuilder =
    TraversalBuilder.empty().add(this, module.shape, Keep.right).makeIsland(islandTag)
}

object LinearTraversalBuilder {

  // TODO: Remove
  private val cachedEmptyLinear = LinearTraversalBuilder(OptionVal.None, OptionVal.None, 0, 0, PushNotUsed, OptionVal.None, Attributes.none)

  private[this] final val wireBackward: Array[Int] = Array(-1)
  private[this] final val noWire: Array[Int] = Array()

  def empty(attributes: Attributes = Attributes.none): LinearTraversalBuilder =
    if (attributes eq Attributes.none) cachedEmptyLinear
    else LinearTraversalBuilder(OptionVal.None, OptionVal.None, 0, 0, PushNotUsed, OptionVal.None, attributes, EmptyTraversal)

  /**
   * Create a traversal builder specialized for linear graphs. This is designed to be much faster and lightweight
   * than its generic counterpart. It can be freely mixed with the generic builder in both ways.
   */
  def fromModule(module: AtomicModule[Shape, Any], attributes: Attributes): LinearTraversalBuilder = {
    if (module.shape.inlets.size > 1) throw new IllegalStateException("Modules with more than one input port cannot be linear.")
    if (module.shape.outlets.size > 1) throw new IllegalStateException("Modules with more than one input port cannot be linear.")
    TraversalBuilder.initShape(module.shape)

    val inPortOpt = OptionVal(module.shape.inlets.headOption.orNull)
    val outPortOpt = OptionVal(module.shape.outlets.headOption.orNull)

    val wiring = if (outPortOpt.isDefined) wireBackward else noWire

    LinearTraversalBuilder(
      inPortOpt,
      outPortOpt,
      inOffset = 0,
      if (inPortOpt.isDefined) 1 else 0,
      traversalSoFar = MaterializeAtomic(module, wiring),
      pendingBuilder = None,
      attributes)
  }

  /**
   * Create a traversal builder specialized for linear graphs. This is designed to be much faster and lightweight
   * than its generic counterpart. It can be freely mixed with the generic builder in both ways.
   */
  def fromKnownLinearModule(module: GraphStageModule[LinearShape, Any], in: Option[Inlet[_]], out: Option[Outlet[_]], attributes: Attributes): LinearTraversalBuilder = {
    TraversalBuilder.initShape(module.shape)

    val wiring = if (out.isDefined) wireBackward else noWire

    LinearTraversalBuilder(
      in,
      out,
      inOffset = 0,
      if (in.isDefined) 1 else 0,
      traversalSoFar = MaterializeAtomic(module, wiring),
      pendingBuilder = OptionVal.None,
      attributes)
  }

  def addMatCompose[A, B](t: Traversal, matCompose: (A, B) ⇒ Any): Traversal = {
    if (matCompose eq Keep.left)
      Pop.concat(t)
    else if (matCompose eq Keep.right)
      t.concat(Pop)
    else // TODO: Optimize this case so the extra function allocation is not needed. Maybe ReverseCompose?
      t.concat(Compose((second, first) ⇒ matCompose.asInstanceOf[(Any, Any) ⇒ Any](first, second)))
  }

  def fromBuilder[A, B](
    traversalBuilder: TraversalBuilder,
    shape:            Shape,
    combine:          (A, B) ⇒ Any     = Keep.right[A, B]): LinearTraversalBuilder = {
    traversalBuilder match {
      case linear: LinearTraversalBuilder ⇒
        if (combine eq Keep.right) linear
        else empty().append(linear, combine)

      case completed: CompletedTraversalBuilder ⇒
        val inOpt = OptionVal(shape.inlets.headOption.orNull)
        val inOffs = inOpt match {
          case OptionVal.Some(in) ⇒ completed.offsetOf(in)
          case OptionVal.None     ⇒ 0
        }

        LinearTraversalBuilder(
          inPort = OptionVal(inOpt.orNull),
          outPort = OptionVal.None,
          inOffset = inOffs,
          inSlots = completed.inSlots,
          completed.traversal.concat(addMatCompose(PushNotUsed, combine)),
          pendingBuilder = OptionVal.None,
          Attributes.none)

      case composite ⇒
        val inOpt = OptionVal(shape.inlets.headOption.orNull)
        val out = shape.outlets.head // Cannot be empty, otherwise it would be a CompletedTraversalBuilder
        val inOffs = inOpt match {
          case OptionVal.Some(in) ⇒ composite.offsetOf(in)
          case OptionVal.None     ⇒ 0
        }

        LinearTraversalBuilder(
          inPort = OptionVal(inOpt.orNull),
          outPort = OptionVal.Some(out),
          inOffset = inOffs,
          inSlots = composite.inSlots,
          addMatCompose(PushNotUsed, combine),
          pendingBuilder = OptionVal.Some(composite),
          Attributes.none,
          beforeBuilder = EmptyTraversal)

    }
  }
}

/**
 * Traversal builder that is optimized for linear graphs (those that contain modules with at most one input and
 * at most one output port). The Traversal is simply built up in reverse order and output ports are automatically
 * assigned to -1 due to the nature of the graph. The only exception is when composites created by
 * [[CompositeTraversalBuilder]] are embedded. These are not guaranteed to have their unwired input/output ports
 * in a fixed location, therefore the last step of the Traversal might need to be changed in those cases from the
 * -1 relative offset to something else (see rewireLastOutTo).
 */
final case class LinearTraversalBuilder(
  inPort:               OptionVal[InPort],
  outPort:              OptionVal[OutPort],
  inOffset:             Int,
  override val inSlots: Int,
  traversalSoFar:       Traversal,
  pendingBuilder:       OptionVal[TraversalBuilder],
  attributes:           Attributes,
  beforeBuilder:        Traversal                   = EmptyTraversal,
  islandTag:            OptionVal[IslandTag]        = OptionVal.None) extends TraversalBuilder {

  protected def isEmpty: Boolean = inSlots == 0 && outPort.isEmpty

  override def add[A, B, C](submodule: TraversalBuilder, shape: Shape, combineMat: (A, B) ⇒ C): TraversalBuilder = {
    throw new UnsupportedOperationException("LinearTraversal does not support free-form addition. Add it into a" +
      "composite builder instead and add the second module to that.")
  }

  /**
   * This builder can always return a traversal.
   */
  override def traversal: Traversal = {
    if (outPort.isDefined)
      throw new IllegalStateException("Traversal cannot be acquired until all output ports have been wired")
    applyIslandAndAttributes(traversalSoFar)
  }

  override def internalSetAttributes(attributes: Attributes): LinearTraversalBuilder =
    copy(attributes = attributes)

  override def setAttributes(attributes: Attributes): LinearTraversalBuilder = {
    if (attributes.isAsync) this.makeIsland(GraphStageTag).internalSetAttributes(attributes)
    else internalSetAttributes(attributes)
  }

  private def applyIslandAndAttributes(t: Traversal): Traversal = {
    val withIslandTag = islandTag match {
      case OptionVal.None      ⇒ t
      case OptionVal.Some(tag) ⇒ EnterIsland(tag).concat(t).concat(ExitIsland)
    }

    if (attributes eq Attributes.none) withIslandTag
    else PushAttributes(attributes).concat(withIslandTag).concat(PopAttributes)
  }

  /**
   * In case the default relative wiring of -1 is not applicable (due to for example an embedded composite
   * [[CompositeTraversalBuilder]] created traversal) we need to change the mapping for the module we added
   * last. This method tears down the traversal until it finds that [[MaterializeAtomic]], changes the mapping,
   * then rebuilds the Traversal.
   */
  private def rewireLastOutTo(traversal: Traversal, relativeOffset: Int): Traversal = {
    // If, by luck, the offset is the same as it would be in the normal case, no transformation is needed
    if (relativeOffset == -1) traversal
    else traversal.rewireFirstTo(relativeOffset)
  }

  /**
   * Since this is a linear traversal, this should not be called in most of the cases. The only notable
   * exception is when a Flow is wired to itself.
   */
  override def wire(out: OutPort, in: InPort): TraversalBuilder = {
    if (outPort.contains(out) && inPort.contains(in)) {
      pendingBuilder match {
        case OptionVal.Some(composite) ⇒
          copy(
            inPort = OptionVal.None,
            outPort = OptionVal.None,
            traversalSoFar =
              applyIslandAndAttributes(
                beforeBuilder.concat(
                  composite
                  .assign(out, inOffset - composite.offsetOfModule(out))
                  .traversal).concat(traversalSoFar)),
            pendingBuilder = OptionVal.None, beforeBuilder = EmptyTraversal)
        case OptionVal.None ⇒
          copy(inPort = OptionVal.None, outPort = OptionVal.None, traversalSoFar = rewireLastOutTo(traversalSoFar, inOffset))
        case OptionVal.None ⇒
          copy(
            inPort = OptionVal.None,
            outPort = OptionVal.None,
            traversalSoFar = rewireLastOutTo(traversalSoFar, inOffset))
      }
    } else
      throw new IllegalArgumentException(s"The ports $in and $out cannot be accessed in this builder.")
  }

  override def offsetOfModule(out: OutPort): Int = {
    if (outPort.contains(out)) {
      pendingBuilder match {
        case OptionVal.Some(composite) ⇒ composite.offsetOfModule(out)
        case OptionVal.None            ⇒ 0 // Output belongs to the last module, which will be materialized *first*
      }
    } else
      throw new IllegalArgumentException(s"Port $out cannot be accessed in this builder")
  }

  override def isUnwired(out: OutPort): Boolean = outPort.contains(out)
  override def isUnwired(in: InPort): Boolean = inPort.contains(in)

  override def offsetOf(in: InPort): Int = {
    if (inPort.contains(in)) inOffset
    else
      throw new IllegalArgumentException(s"Port $in cannot be accessed in this builder")
  }

  override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
    if (outPort.contains(out)) {
      pendingBuilder match {
        case OptionVal.Some(composite) ⇒
          copy(
            outPort = OptionVal.None,
            traversalSoFar =
              applyIslandAndAttributes(
                beforeBuilder.concat(
                  composite
                    .assign(out, relativeSlot)
                    .traversal
                    .concat(traversalSoFar))),
            pendingBuilder = OptionVal.None,
            beforeBuilder = EmptyTraversal)
        case OptionVal.None ⇒
          copy(outPort = OptionVal.None, traversalSoFar = rewireLastOutTo(traversalSoFar, relativeSlot))
          copy(
            outPort = OptionVal.None,
            traversalSoFar = rewireLastOutTo(traversalSoFar, relativeSlot))
      }
    } else
      throw new IllegalArgumentException(s"Port $out cannot be assigned in this builder")
  }

  override def isTraversalComplete: Boolean = outPort.isEmpty

  override def unwiredOuts: Int = if (outPort.isDefined) 1 else 0

  def append[A, B, C](toAppend: TraversalBuilder, shape: Shape, matCompose: (A, B) ⇒ C): LinearTraversalBuilder =
    append(LinearTraversalBuilder.fromBuilder(toAppend, shape, Keep.right), matCompose)

  // We don't really need the Shape for the linear append, but it is nicer to keep the API uniform here
  /**
   * Append any builder that is linear shaped (have at most one input and at most one output port) to the
   * end of this graph, connecting the output of the last module to the input of the appended module.
   */
  def append[A, B, C](toAppend: LinearTraversalBuilder, matCompose: (A, B) ⇒ C): LinearTraversalBuilder = {

    if (toAppend.isEmpty) {
      copy(
        traversalSoFar = PushNotUsed.concat(LinearTraversalBuilder.addMatCompose(traversalSoFar, matCompose)))
    } else if (this.isEmpty) {
      toAppend.copy(
        traversalSoFar = toAppend.traversalSoFar.concat(LinearTraversalBuilder.addMatCompose(traversal, matCompose)))
    } else {
      if (outPort.isDefined) {
        if (toAppend.inPort.isEmpty)
          throw new IllegalArgumentException("Appended linear module must have an unwired input port because there is a dangling output.")

        /*
         * To understand how append work, first the general structure of the LinearTraversalBuilder must be
         * understood. The most general structure of LinearTraversalBuilder looks like this (in Flow DSL order):
         *
         *   traversalSoFar ~ pendingBuilder ~ beforeBuilder
         *
         * The reason for this is that composite builders cannot provide a Traversal until all of their output
         * ports have been wired, so we cannot just concat their traversal to traversalSoFar until a new
         * LinearTraversalBuilder is appended (which will wire the output port and hence finish the traversal).
         * The reason for beforeBuilder (which is a Traversal) is to collect PushAttribute and EnterIsland steps
         * which need to be applied before visiting the Traversal that will be returned by pendingBuilder.
         *
         * Remember also, that the Traversal is built in _reverse_ order, hence, if you look at the ordering from
         * the Traversal perspective it will look like this:
         *
         *   beforeBuilder ~ pendingBuilder ~ traversalSoFar
         *
         * If the LinearTraversalBuilder had a "pure" LinearTraversalBuilder appended last, then the structure is
         * simply:
         *
         *   traversalSoFar
         *
         * This is because pure LinearTraversalBuilders can always provide the full Traversal. This is achieved by
         * the last output port assigned to the -1 relative offset, optimistically. Remember that Traversal is
         * built in the _reverse_ order, hence downstreams are visited before upstreams, and hence output ports
         * are wired _back_. The assignment to -1 is sometimes incorrect though, because an appended Traversal, even
         * if it is a pure linear at that point might had a composite included at some point (which is now completed).
         * If the input port of that composite is exposed, it is not guaranteed that it is the last input port that
         * is visited in the (reverse) Traversal order. In this case this optimistic assignment to -1 needs to be
         * corrected, which is what rewireLastOutTo is used for.
         *
         * Please refer to the comments detailing these steps below.
         */

        /*
         * As a first step, we construct the full traversal for the LinearTraversalBuilder that is appended to.
         * Depending whether we have a composite builder as our last step or not, composition will be somewhat
         * different.
         */
        val assembledTraversalForThis = this.pendingBuilder match {
          case OptionVal.None ⇒
            /*
             * This is the case where we are a pure linear builder (all composites have been already completed),
             * which means that traversalSoFar contains everything already, except the final attributes and islands
             * applied.
             *
             * Since the exposed output port has been wired optimistically to -1, we need to check if this is correct,
             * and correct if necessary. This is the step below:
             */
            if (toAppend.inOffset == (toAppend.inSlots - 1)) {
              /*
               * if the builder we want to append (remember that is _prepend_ from the Traversal's perspective)
               * has its exposed input port at the last location (which is toAppend.inSlots - 1 because input
               * port offsets start with 0), then -1 is the correct wiring. I.e.
               *
               * 1. Visit the appended module first in the traversal, its input port is the last
               * 2. Visit this module second in the traversal, wire the output port back to the previous input port (-1)
               */
              traversalSoFar
            } else {
              /*
               * The optimistic mapping to -1 is not correct, we need to unfold the Traversal to find our last module
               * (which is the _first_ module in the Traversal) and rewire the output assignment to the correct offset.
               *
               * Since we will be visited second (and the appended toAppend first), we need to
               *
               * 1. go backward toAppend.inSlots slots to reach the beginning offset of toAppend
               * 2. now go forward toAppend.inOffset to reach the correct location
               *
               * <-------------- (-toAppend.inSlots)
               * ------->        (+toAppend.inOffset)
               *
               * --------in----|[out module]----------
               *    toAppend           this
               *
               */
              rewireLastOutTo(traversalSoFar, toAppend.inOffset - toAppend.inSlots)
            }

          case OptionVal.Some(composite) ⇒
            /*
             * This is the case where our last module is a composite, and since it does not have its output port
             * wired yet, the traversal is split into the parts, traversalSoFar, pendingBuilder and beforeBuilder.
             *
             * Since will wire now the output port, we can assemble everything together:
             */
            val out = outPort.get
            /*
             * Since we will be visited second (and the appended toAppend first), we need to
             *
             * 1. go back to the start of the composite module, i.e. composite.offsetOfModule(out) steps. This
             *    is necessary because the composite might not have the internal module as the first visited
             *    module in the Traversal and hence not have a base offset of 0 in the composite
             * 2. go backward toAppend.inSlots slots to reach the beginning offset of toAppend
             * 3. now go forward toAppend.inOffset to reach the correct location
             *
             *                <------- (-composite.offsetOfModule(out))
             * <--------------         (-toAppend.inSlots)
             * ------->                (+toAppend.inOffset)
             *
             * --------in----|-------[out module]----------
             *    toAppend                this
             *
             */
            val compositeTraversal = composite
              .assign(out, -composite.offsetOfModule(out) - toAppend.inSlots + toAppend.inOffset)
              .traversal // All output ports are finished, so we can finally call this now

            /*
             * Now we can assemble the pieces for the final Traversal of _this_ builder.
             *
             * beforeBuilder ~ compositeTraversal ~ traversalSoFar
             *
             * (remember that this is the _reverse_ of the Flow DSL order)
             */
            beforeBuilder
              .concat(compositeTraversal)
              .concat(traversalSoFar)
        }

        /*
         * We have almost finished the traversal for _this_ builder, we only need to apply attributes and islands.
         */
        val finalTraversalForThis = {
          // Now add the island tags, attributes, and the opcodes that will compose the materialized values with toAppend
          LinearTraversalBuilder.addMatCompose(applyIslandAndAttributes(assembledTraversalForThis), matCompose)
        }

        /*
         * We have finished "this" builder, now we need to construct the new builder as the result of appending.
         * There are two variants, depending whether toAppend is purely linear or if it has a composite at the end.
         */
        toAppend.pendingBuilder match {
          case OptionVal.None ⇒
            /*
             * This is the simple case, when the other is purely linear. We just concatenate the traversals
             * and do some bookkeeping.
             */
            LinearTraversalBuilder(
              inPort = inPort,
              outPort = toAppend.outPort,
              inSlots = inSlots + toAppend.inSlots, // we have now more input ports than before
              // the inOffset of _this_ gets shifted by toAppend.inSlots, because the traversal of toAppend is _prepended_
              inOffset = inOffset + toAppend.inSlots,
              // Build in reverse so it yields a more efficient layout for left-to-right building
              traversalSoFar = toAppend.applyIslandAndAttributes(toAppend.traversalSoFar).concat(finalTraversalForThis),
              pendingBuilder = OptionVal.None,
              attributes = Attributes.none, // attributes are none for the new enclosing builder
              beforeBuilder = EmptyTraversal, // no need for beforeBuilder as there are no composites
              islandTag = OptionVal.None // islandTag is reset for the new enclosing builder
            )

          case OptionVal.Some(composite) ⇒
            /*
             * In this case we need to assemble as much as we can, and create a new "sandwich" of
             *   beforeBuilder ~ pendingBuilder ~ traversalSoFar
             *
             * We need to apply the attributes and islandTags of the appended builder, but we cannot do it in one
             * step, instead we need to append half of the steps to traversalSoFar, and the other half to
             * beforeBuilder.
             */
            var newTraversalSoFar = finalTraversalForThis
            var newBeforeTraversal = toAppend.beforeBuilder

            // First prepare island enter and exit if tags are present
            toAppend.islandTag match {
              case OptionVal.None ⇒
              // Nothing changes
              case OptionVal.Some(tag) ⇒
                // Enter the island just before the appended builder (keeping the toAppend.beforeBuilder steps)
                newBeforeTraversal = EnterIsland(tag).concat(newBeforeTraversal)
                // Exit the island just after the appended builder (they should not applied to _this_ builder)
                newTraversalSoFar = ExitIsland.concat(newTraversalSoFar)
            }

            // Secondly, prepare attribute push and pop if Attributes are present
            if (toAppend.attributes ne Attributes.none) {
              // Push the attributes just before the appended builder.
              newBeforeTraversal = PushAttributes(toAppend.attributes).concat(newBeforeTraversal)
              // Pop the attributes immediately after the appended builder (they should not applied to _this_ builder)
              newTraversalSoFar = PopAttributes.concat(newTraversalSoFar)
            }

            // Finally add the already completed part of toAppend to newTraversalSoFar
            newTraversalSoFar = toAppend.traversalSoFar.concat(newTraversalSoFar)

            LinearTraversalBuilder(
              inPort = inPort,
              outPort = toAppend.outPort,
              inSlots = inSlots + toAppend.inSlots, // we have now more input ports than before
              // the inOffset of _this_ gets shifted by toAppend.inSlots, because the traversal of toAppend is _prepended_
              inOffset = inOffset + toAppend.inSlots,
              // Build in reverse so it yields a more efficient layout for left-to-right building. We cannot
              // apply the full traversal, only the completed part of it
              traversalSoFar = newTraversalSoFar,
              // Last composite of toAppend is still pending
              pendingBuilder = toAppend.pendingBuilder,
              attributes = Attributes.none, // attributes are none for the new enclosing builder
              beforeBuilder = newBeforeTraversal, // no need for beforeBuilder as there are no composites
              islandTag = OptionVal.None // islandTag is reset for the new enclosing builder
            )
        }
      } else throw new Exception("should this happen?")

    }

  }

  override def transformMat[A, B](f: (A) ⇒ B): LinearTraversalBuilder = {
    copy(traversalSoFar = traversalSoFar.concat(Transform(f.asInstanceOf[Any ⇒ Any])))
  }

  /**
   * Wraps the builder in an island that can be materialized differently, using async boundaries to bridge
   * between islands.
   */
  override def makeIsland(islandTag: IslandTag): LinearTraversalBuilder =
    this.islandTag match {
      case OptionVal.Some(tag) ⇒ this // Wrapping with an island, then immediately re-wrapping makes the second island empty, so can be omitted
      case OptionVal.None      ⇒ copy(islandTag = OptionVal.Some(islandTag))
    }
}

sealed trait TraversalBuildStep
/**
 * Helper class that is only used to identify a [[TraversalBuilder]] in a [[CompositeTraversalBuilder]]. The
 * reason why this is needed is because the builder is referenced at various places, while it needs to be mutated.
 * In an immutable data structure this is best done with an indirection, i.e. places refer to this immutable key and
 * look up the current state in an extra Map.
 */
final class BuilderKey extends TraversalBuildStep {
  override def toString = s"K:$hashCode"
}
final case class AppendTraversal(traversal: Traversal) extends TraversalBuildStep

/**
 * A generic builder that builds a traversal for graphs of arbitrary shape. The memory retained by this class
 * usually decreases as ports are wired since auxiliary data is only maintained for ports that are unwired.
 *
 * This builder MUST construct its Traversal in the *exact* same order as its modules were added, since the first
 * (and subsequent) input port of a module is implicitly assigned by its traversal order. Emitting Traversal nodes
 * in a non-deterministic order (depending on wiring order) would mess up all relative addressing. This is the
 * primary technical reason why a reverseTraversal list is maintained and the Traversal can only be completed once
 * all output ports have been wired.
 *
 * @param reverseBuildSteps Keeps track of traversal steps that needs to be concatenated. This is basically
 *                         a "queue" of BuilderKeys that point to builders of submodules/subgraphs. Since it is
 *                         unknown in which order will the submodules "complete" (have all of their outputs assigned)
 *                         we delay the creation of the actual Traversal.
 * @param inSlots          The number of input ports this graph has in total.
 * @param inOffsets        Map to look up the offset of input ports not yet wired
 * @param inBaseOffsetForOut Map to look up the base (input) offset of a module that owns the given output port
 * @param pendingBuilders  Map to contain the "mutable" builders referred by BuilderKeys
 * @param outOwners        Map of output ports to their parent builders (actually the BuilderKey)
 * @param unwiredOuts      Number of output ports that have not yet been wired/assigned
 */
final case class CompositeTraversalBuilder(
  finalSteps:         Traversal                         = EmptyTraversal,
  reverseBuildSteps:  List[TraversalBuildStep]          = AppendTraversal(PushNotUsed) :: Nil,
  inSlots:            Int                               = 0,
  inOffsets:          Map[InPort, Int]                  = Map.empty,
  inBaseOffsetForOut: Map[OutPort, Int]                 = Map.empty,
  pendingBuilders:    Map[BuilderKey, TraversalBuilder] = Map.empty,
  outOwners:          Map[OutPort, BuilderKey]          = Map.empty,
  unwiredOuts:        Int                               = 0,
  attributes:         Attributes,
  islandTag:          OptionVal[IslandTag]              = OptionVal.None) extends TraversalBuilder {

  override def toString: String =
    s"""
         |CompositeTraversal(
         |  reverseTraversal = $reverseBuildSteps
         |  pendingBuilders = $pendingBuilders
         |  inSlots = $inSlots
         |  inOffsets = $inOffsets
         |  inBaseOffsetForOut = $inBaseOffsetForOut
         |  outOwners = $outOwners
         |  unwiredOuts = $unwiredOuts
         |)
       """.stripMargin

  override def offsetOfModule(out: OutPort): Int = inBaseOffsetForOut(out)
  override def isUnwired(out: OutPort): Boolean = inBaseOffsetForOut.contains(out)
  override def isUnwired(in: InPort): Boolean = inOffsets.contains(in)

  override def offsetOf(in: InPort): Int = inOffsets(in)
  override def isTraversalComplete = false

  override def internalSetAttributes(attributes: Attributes): TraversalBuilder =
    copy(attributes = attributes)

  /**
   * Convert this builder to a [[CompositeTraversalBuilder]] if there are no more unwired outputs.
   */
  def completeIfPossible: TraversalBuilder = {
    if (unwiredOuts == 0) {
      var traversal: Traversal = finalSteps
      var remaining = reverseBuildSteps
      while (remaining.nonEmpty) {
        remaining.head match {
          case key: BuilderKey ⇒
            // At this point all the builders we have are completed and we can finally build our traversal
            traversal = pendingBuilders(key).traversal.concat(traversal)
          case AppendTraversal(toAppend) ⇒
            traversal = toAppend.concat(traversal)
        }
        remaining = remaining.tail
      }

      val finalTraversal = islandTag match {
        case OptionVal.None      ⇒ traversal
        case OptionVal.Some(tag) ⇒ EnterIsland(tag).concat(traversal).concat(ExitIsland)
      }

      // The CompleteTraversalBuilder only keeps the minimum amount of necessary information that is needed for it
      // to be embedded in a larger graph, making partial graph reuse much more efficient.
      CompletedTraversalBuilder(
        traversalSoFar = finalTraversal,
        inSlots,
        inOffsets,
        attributes)
    } else this
  }

  /**
   * Assign an output port a relative slot (relative to the base input slot of its module, see [[MaterializeAtomic]])
   */
  override def assign(out: OutPort, relativeSlot: Int): TraversalBuilder = {
    // Which module out belongs to (indirection via BuilderKey and pendingBuilders)
    val builderKey = outOwners(out)
    val submodule = pendingBuilders(builderKey)

    // Do the assignment in the submodule
    val result = submodule.assign(out.mappedTo, relativeSlot)
    val wired = if (result.isTraversalComplete) {
      // Remove the builder (and associated data).
      // We can't simply append its Traversal as there might be uncompleted builders that come earlier in the
      // final traversal (remember, input ports are assigned in traversal order of modules, and the inOffsets
      // and inBaseOffseForOut Maps are updated when adding a module; we must respect addition order).

      copy(
        inBaseOffsetForOut = inBaseOffsetForOut - out,
        outOwners = outOwners - out,
        // TODO Optimize Map access
        pendingBuilders = pendingBuilders.updated(builderKey, result),
        // pendingBuilders = pendingBuilders - builderKey,
        unwiredOuts = unwiredOuts - 1)
    } else {
      // Update structures with result
      copy(
        inBaseOffsetForOut = inBaseOffsetForOut - out,
        unwiredOuts = unwiredOuts - 1,
        pendingBuilders = pendingBuilders.updated(builderKey, result))
    }

    // If we have no more unconnected outputs, we can finally build the Traversal and shed most of the auxiliary data.
    wired.completeIfPossible
  }

  // Requires that a remapped Shape's ports contain the same ID as their target ports!
  def add[A, B, C](submodule: TraversalBuilder, shape: Shape, combineMat: (A, B) ⇒ C): TraversalBuilder = {
    val builderKey = new BuilderKey

    val newBuildSteps =
      if (combineMat == Keep.left) {
        AppendTraversal(Pop) ::
          builderKey ::
          reverseBuildSteps
      } else if (combineMat == Keep.right) {
        builderKey ::
          AppendTraversal(Pop) ::
          reverseBuildSteps
      } else if (combineMat == Keep.none) {
        AppendTraversal(PushNotUsed) ::
          AppendTraversal(Pop) ::
          AppendTraversal(Pop) ::
          builderKey ::
          reverseBuildSteps
      } else {
        AppendTraversal(Compose(combineMat.asInstanceOf[(Any, Any) ⇒ Any])) ::
          builderKey ::
          reverseBuildSteps
      }

    val added = if (submodule.isTraversalComplete) {
      // We only need to keep track of the offsets of unwired inputs. Outputs have all been wired
      // (isTraversalComplete = true).

      var newInOffsets = inOffsets
      val inIterator = shape.inlets.iterator
      while (inIterator.hasNext) {
        val in = inIterator.next()
        // Calculate offset in the current scope. This is the our first unused input slot plus
        // the relative offset of the input port in the submodule.
        // TODO Optimize Map access
        newInOffsets = newInOffsets.updated(in, inSlots + submodule.offsetOf(in.mappedTo))
      }

      copy(
        reverseBuildSteps = newBuildSteps,
        inSlots = inSlots + submodule.inSlots,
        pendingBuilders = pendingBuilders.updated(builderKey, submodule),
        inOffsets = newInOffsets)
    } else {
      // Added module have unwired outputs.

      var newInOffsets = inOffsets
      var newBaseOffsetsForOut = inBaseOffsetForOut
      var newOutOwners = outOwners

      // See the other if case for explanation of this
      val inIterator = shape.inlets.iterator
      while (inIterator.hasNext) {
        val in = inIterator.next()
        // Calculate offset in the current scope
        // TODO Optimize Map access
        newInOffsets = newInOffsets.updated(in, inSlots + submodule.offsetOf(in.mappedTo))
      }

      val outIterator = shape.outlets.iterator
      while (outIterator.hasNext) {
        val out = outIterator.next()
        // Record the base offsets of all the modules we included and which have unwired output ports. We need
        // to adjust their offset by inSlots as that would be their new position in this module.
        newBaseOffsetsForOut = newBaseOffsetsForOut.updated(out, inSlots + submodule.offsetOfModule(out.mappedTo))
        // TODO Optimize Map access
        newOutOwners = newOutOwners.updated(out, builderKey)
      }

      copy(
        reverseBuildSteps = newBuildSteps,
        inSlots = inSlots + submodule.inSlots,
        inOffsets = newInOffsets,
        inBaseOffsetForOut = newBaseOffsetsForOut,
        outOwners = newOutOwners,
        pendingBuilders = pendingBuilders.updated(builderKey, submodule),
        unwiredOuts = unwiredOuts + submodule.unwiredOuts)
    }

    added.completeIfPossible
  }

  def wire(out: OutPort, in: InPort): TraversalBuilder = {
    copy(inOffsets = inOffsets - in).assign(out, offsetOf(in) - offsetOfModule(out))
  }

  override def transformMat[A, B](f: (A) ⇒ B): TraversalBuilder = {
    copy(finalSteps = finalSteps.concat(Transform(f.asInstanceOf[Any ⇒ Any])))
  }

  override def makeIsland(islandTag: IslandTag): TraversalBuilder = {
    this.islandTag match {
      case OptionVal.None ⇒ copy(islandTag = OptionVal(islandTag))
      case _              ⇒ this // Wrapping with an island, then immediately re-wrapping makes the second island empty, so can be omitted
    }
  }
}
