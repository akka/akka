/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.scaladsl.{ Keep, OperationAttributes }
import akka.stream._
import org.reactivestreams.{ Subscription, Publisher, Subscriber }
import akka.event.Logging.simpleName
import scala.collection.mutable

/**
 * INTERNAL API
 */
private[akka] object StreamLayout {

  // compile-time constant
  val debug = true

  // TODO: Materialization order
  // TODO: Special case linear composites
  // TODO: Cycles

  sealed trait MaterializedValueNode
  case class Combine(f: (Any, Any) ⇒ Any, dep1: MaterializedValueNode, dep2: MaterializedValueNode) extends MaterializedValueNode
  case class Atomic(module: Module) extends MaterializedValueNode
  case class Transform(f: Any ⇒ Any, dep: MaterializedValueNode) extends MaterializedValueNode
  case object Ignore extends MaterializedValueNode

  trait Module {
    def shape: Shape
    /**
     * Verify that the given Shape has the same ports and return a new module with that shape.
     * Concrete implementations may throw UnsupportedOperationException where applicable.
     */
    def replaceShape(s: Shape): Module

    lazy val inPorts: Set[InPort] = shape.inlets.toSet
    lazy val outPorts: Set[OutPort] = shape.outlets.toSet

    def isRunnable: Boolean = inPorts.isEmpty && outPorts.isEmpty
    def isSink: Boolean = (inPorts.size == 1) && outPorts.isEmpty
    def isSource: Boolean = (outPorts.size == 1) && inPorts.isEmpty
    def isFlow: Boolean = (inPorts.size == 1) && (outPorts.size == 1)
    def isBidiFlow: Boolean = (inPorts.size == 2) && (outPorts.size == 2)

    def growConnect(that: Module, from: OutPort, to: InPort): Module =
      growConnect(that, from, to, Keep.left)

    def growConnect[A, B, C](that: Module, from: OutPort, to: InPort, f: (A, B) ⇒ C): Module =
      this.grow(that, f).connect(from, to)

    def connect[A, B](from: OutPort, to: InPort): Module = {
      if (debug) validate()

      require(outPorts(from),
        if (downstreams.contains(from)) s"The output port [$from] is already connected"
        else s"The output port [$from] is not part of the underlying graph.")
      require(inPorts(to),
        if (upstreams.contains(to)) s"The input port [$to] is already connected"
        else s"The input port [$to] is not part of the underlying graph.")

      CompositeModule(
        subModules,
        AmorphousShape(shape.inlets.filterNot(_ == to), shape.outlets.filterNot(_ == from)),
        (from, to) :: connections,
        materializedValueComputation,
        attributes)
    }

    def transformMaterializedValue(f: Any ⇒ Any): Module = {
      if (debug) validate()

      CompositeModule(
        subModules = if (this.isAtomic) Set(this) else this.subModules,
        shape,
        connections,
        Transform(f, this.materializedValueComputation),
        attributes)
    }

    def grow(that: Module): Module = grow(that, Keep.left)

    def grow[A, B, C](that: Module, f: (A, B) ⇒ C): Module = {
      if (debug) validate()

      require(that ne this, "A module cannot be added to itself. You should pass a separate instance to grow().")
      require(!subModules(that), "An existing submodule cannot be added again. All contained modules must be unique.")

      val modules1 = if (this.isAtomic) Set(this) else this.subModules
      val modules2 = if (that.isAtomic) Set(that) else that.subModules

      CompositeModule(
        modules1 ++ modules2,
        AmorphousShape(shape.inlets ++ that.shape.inlets, shape.outlets ++ that.shape.outlets),
        connections reverse_::: that.connections,
        if (f eq Keep.left) materializedValueComputation
        else if (f eq Keep.right) that.materializedValueComputation
        else Combine(f.asInstanceOf[(Any, Any) ⇒ Any], this.materializedValueComputation, that.materializedValueComputation),
        attributes)
    }

    def wrap(): Module = {
      if (debug) validate()

      CompositeModule(
        subModules = Set(this),
        shape,
        connections,
        /*
         * Wrapping like this shields the outer module from the details of the
         * materialized value computation of its submodules, which is important
         * to keep the re-binding of identities to computation nodes manageable
         * in carbonCopy.
         */
        Atomic(this),
        OperationAttributes.none)
    }

    def subModules: Set[Module]
    def isAtomic: Boolean = subModules.isEmpty

    /**
     * A list of connections whose port-wise ordering is STABLE across carbonCopy.
     */
    def connections: List[(OutPort, InPort)] = Nil
    final lazy val downstreams: Map[OutPort, InPort] = connections.toMap
    final lazy val upstreams: Map[InPort, OutPort] = connections.map(_.swap).toMap

    def materializedValueComputation: MaterializedValueNode = Atomic(this)
    def carbonCopy: Module

    def attributes: OperationAttributes
    def withAttributes(attributes: OperationAttributes): Module

    final override def hashCode(): Int = super.hashCode()
    final override def equals(obj: scala.Any): Boolean = super.equals(obj)

    def validate(level: Int = 0, doPrint: Boolean = false, idMap: mutable.Map[AnyRef, Int] = mutable.Map.empty): Unit = {
      val ids = Iterator from 1
      def id(obj: AnyRef) = idMap get obj match {
        case Some(x) ⇒ x
        case None ⇒
          val x = ids.next()
          idMap(obj) = x
          x
      }
      def in(i: InPort) = s"${i.toString}@${id(i)}"
      def out(o: OutPort) = s"${o.toString}@${id(o)}"
      def ins(i: Iterable[InPort]) = i.map(in).mkString("In[", ",", "]")
      def outs(o: Iterable[OutPort]) = o.map(out).mkString("Out[", ",", "]")
      def pair(p: (OutPort, InPort)) = s"${in(p._2)}->${out(p._1)}"
      def pairs(p: Iterable[(OutPort, InPort)]) = p.map(pair).mkString("[", ",", "]")

      val inset: Set[InPort] = shape.inlets.toSet
      val outset: Set[OutPort] = shape.outlets.toSet
      var problems: List[String] = Nil

      if (inset.size != shape.inlets.size) problems ::= "shape has duplicate inlets: " + ins(shape.inlets)
      if (inset != inPorts) problems ::= s"shape has extra ${ins(inset -- inPorts)}, module has extra ${ins(inPorts -- inset)}"
      if (inset.intersect(upstreams.keySet).nonEmpty) problems ::= s"found connected inlets ${inset.intersect(upstreams.keySet)}"
      if (outset.size != shape.outlets.size) problems ::= "shape has duplicate outlets: " + outs(shape.outlets)
      if (outset != outPorts) problems ::= s"shape has extra ${outs(outset -- outPorts)}, module has extra ${outs(outPorts -- outset)}"
      if (outset.intersect(downstreams.keySet).nonEmpty) problems ::= s"found connected outlets ${outset.intersect(downstreams.keySet)}"
      val ups = upstreams.toSet
      val ups2 = ups.map(_.swap)
      val downs = downstreams.toSet
      val inter = ups2.intersect(downs)
      if (downs != ups2) problems ::= s"inconsistent maps: ups ${pairs(ups2 -- inter)} downs ${pairs(downs -- inter)}"
      val (allIn, dupIn, allOut, dupOut) =
        subModules.foldLeft((Set.empty[InPort], Set.empty[InPort], Set.empty[OutPort], Set.empty[OutPort])) {
          case ((ai, di, ao, doo), m) ⇒ (ai ++ m.inPorts, di ++ ai.intersect(m.inPorts), ao ++ m.outPorts, doo ++ ao.intersect(m.outPorts))
        }
      if (dupIn.nonEmpty) problems ::= s"duplicate ports in submodules ${ins(dupIn)}"
      if (dupOut.nonEmpty) problems ::= s"duplicate ports in submodules ${outs(dupOut)}"
      if (!isAtomic && (inset -- allIn).nonEmpty) problems ::= s"foreign inlets ${ins(inset -- allIn)}"
      if (!isAtomic && (outset -- allOut).nonEmpty) problems ::= s"foreign outlets ${outs(outset -- allOut)}"
      val unIn = allIn -- inset -- upstreams.keySet
      if (unIn.nonEmpty) problems ::= s"unconnected inlets ${ins(unIn)}"
      val unOut = allOut -- outset -- downstreams.keySet
      if (unOut.nonEmpty) problems ::= s"unconnected outlets ${outs(unOut)}"
      def atomics(n: MaterializedValueNode): Set[Module] =
        n match {
          case Ignore                  ⇒ Set.empty
          case Transform(f, dep)       ⇒ atomics(dep)
          case Atomic(m)               ⇒ Set(m)
          case Combine(f, left, right) ⇒ atomics(left) ++ atomics(right)
        }
      val atomic = atomics(materializedValueComputation)
      if ((atomic -- subModules - this).nonEmpty) problems ::= s"computation refers to non-existent modules [${atomic -- subModules - this mkString ","}]"

      val print = doPrint || problems.nonEmpty

      if (print) {
        val indent = " " * (level * 2)
        println(s"$indent${simpleName(this)}($shape): ${ins(inPorts)} ${outs(outPorts)}")
        downstreams foreach { case (o, i) ⇒ println(s"$indent    ${out(o)} -> ${in(i)}") }
        problems foreach (p ⇒ println(s"$indent  -!- $p"))
      }

      subModules foreach (_.validate(level + 1, print, idMap))

      if (problems.nonEmpty && !doPrint) throw new IllegalStateException(s"module inconsistent, found ${problems.size} problems")
    }
  }

  object EmptyModule extends Module {
    override def shape = ClosedShape
    override def replaceShape(s: Shape) =
      if (s == ClosedShape) this
      else throw new UnsupportedOperationException("cannot replace the shape of the EmptyModule")

    override def grow(that: Module): Module = that
    override def wrap(): Module = this

    override def subModules: Set[Module] = Set.empty

    override def withAttributes(attributes: OperationAttributes): Module =
      throw new UnsupportedOperationException("EmptyModule cannot carry attributes")
    override def attributes = OperationAttributes.none

    override def carbonCopy: Module = this

    override def isRunnable: Boolean = false
    override def isAtomic: Boolean = false
    override def materializedValueComputation: MaterializedValueNode = Ignore
  }

  final case class CompositeModule(
    subModules: Set[Module],
    shape: Shape,
    override val connections: List[(OutPort, InPort)],
    override val materializedValueComputation: MaterializedValueNode,
    attributes: OperationAttributes) extends Module {

    override def replaceShape(s: Shape): Module = {
      shape.requireSamePortsAs(s)
      copy(shape = s)
    }

    override def carbonCopy: Module = {
      val out = mutable.Map[OutPort, OutPort]()
      val in = mutable.Map[InPort, InPort]()
      val subMap = mutable.Map[Module, Module]()

      val subs = subModules map { s ⇒
        val n = s.carbonCopy
        out ++= s.shape.outlets.zip(n.shape.outlets)
        in ++= s.shape.inlets.zip(n.shape.inlets)
        s.connections.zip(n.connections) foreach {
          case ((oldOut, oldIn), (newOut, newIn)) ⇒
            out(oldOut) = newOut
            in(oldIn) = newIn
        }
        subMap(s) = n
        n
      }

      val newShape = shape.copyFromPorts(shape.inlets.map(in.asInstanceOf[Inlet[_] ⇒ Inlet[_]]),
        shape.outlets.map(out.asInstanceOf[Outlet[_] ⇒ Outlet[_]]))

      val conn = connections.map(p ⇒ (out(p._1), in(p._2)))

      def mapComp(n: MaterializedValueNode): MaterializedValueNode =
        n match {
          case Ignore                  ⇒ Ignore
          case Transform(f, dep)       ⇒ Transform(f, mapComp(dep))
          case Atomic(mod)             ⇒ Atomic(subMap(mod))
          case Combine(f, left, right) ⇒ Combine(f, mapComp(left), mapComp(right))
        }
      val comp =
        try mapComp(materializedValueComputation)
        catch {
          case so: StackOverflowError ⇒
            throw new UnsupportedOperationException("materialized value computation is too complex, please group into sub-graphs")
        }

      copy(subModules = subs, shape = newShape, connections = conn, materializedValueComputation = comp)
    }

    override def withAttributes(attributes: OperationAttributes): Module = copy(attributes = attributes)

    override def toString =
      s"""
        | Modules: ${subModules.toSeq.map(m ⇒ "   " + m.getClass.getName).mkString("\n")}
        | Downstreams:
        | ${downstreams.map { case (in, out) ⇒ s"   $in -> $out" }.mkString("\n")}
        | Upstreams:
        | ${upstreams.map { case (out, in) ⇒ s"   $out -> $in" }.mkString("\n")}
      """.stripMargin

  }
}

/**
 * INTERNAL API
 */
private[stream] class VirtualSubscriber[T](val owner: VirtualPublisher[T]) extends Subscriber[T] {
  override def onSubscribe(s: Subscription): Unit = throw new UnsupportedOperationException("This method should not be called")
  override def onError(t: Throwable): Unit = throw new UnsupportedOperationException("This method should not be called")
  override def onComplete(): Unit = throw new UnsupportedOperationException("This method should not be called")
  override def onNext(t: T): Unit = throw new UnsupportedOperationException("This method should not be called")
}

/**
 * INTERNAL API
 */
private[stream] class VirtualPublisher[T]() extends Publisher[T] {
  @volatile var realPublisher: Publisher[T] = null
  override def subscribe(s: Subscriber[_ >: T]): Unit = realPublisher.subscribe(s)
}

/**
 * INTERNAL API
 */
private[stream] abstract class MaterializerSession(val topLevel: StreamLayout.Module) {
  import StreamLayout._

  private val subscribers = collection.mutable.HashMap[InPort, Subscriber[Any]]().withDefaultValue(null)
  private val publishers = collection.mutable.HashMap[OutPort, Publisher[Any]]().withDefaultValue(null)

  final def materialize(): Any = {
    require(topLevel ne EmptyModule, "An empty module cannot be materialized (EmptyModule was given)")
    require(
      topLevel.isRunnable,
      s"The top level module cannot be materialized because it has unconnected ports: ${(topLevel.inPorts ++ topLevel.outPorts).mkString(", ")}")
    materializeModule(topLevel, topLevel.attributes)
  }

  protected def mergeAttributes(parent: OperationAttributes, current: OperationAttributes): OperationAttributes =
    parent and current

  protected def materializeModule(module: Module, effectiveAttributes: OperationAttributes): Any = {
    val materializedValues = collection.mutable.HashMap.empty[Module, Any]
    for (submodule ← module.subModules) {
      val subEffectiveAttributes = mergeAttributes(effectiveAttributes, submodule.attributes)
      if (submodule.isAtomic) materializedValues.put(submodule, materializeAtomic(submodule, subEffectiveAttributes))
      else materializedValues.put(submodule, materializeComposite(submodule, subEffectiveAttributes))
    }
    resolveMaterialized(module.materializedValueComputation, materializedValues)
  }

  protected def materializeComposite(composite: Module, effectiveAttributes: OperationAttributes): Any = {
    materializeModule(composite, effectiveAttributes)
  }

  protected def materializeAtomic(atomic: Module, effectiveAttributes: OperationAttributes): Any

  private def resolveMaterialized(matNode: MaterializedValueNode, materializedValues: collection.Map[Module, Any]): Any = matNode match {
    case Atomic(m)          ⇒ materializedValues(m)
    case Combine(f, d1, d2) ⇒ f(resolveMaterialized(d1, materializedValues), resolveMaterialized(d2, materializedValues))
    case Transform(f, d)    ⇒ f(resolveMaterialized(d, materializedValues))
    case Ignore             ⇒ ()
  }

  private def attach(p: Publisher[Any], s: Subscriber[Any]) = s match {
    case v: VirtualSubscriber[Any] ⇒ v.owner.realPublisher = p
    case _                         ⇒ p.subscribe(s)
  }

  final protected def assignPort(in: InPort, subscriber: Subscriber[Any]): Unit = {
    subscribers.put(in, subscriber)
    val publisher = publishers(topLevel.upstreams(in))
    if (publisher ne null) attach(publisher, subscriber)
  }

  final protected def assignPort(out: OutPort, publisher: Publisher[Any]): Unit = {
    publishers.put(out, publisher)
    val subscriber = subscribers(topLevel.downstreams(out))
    if (subscriber ne null) attach(publisher, subscriber)
  }

}
