/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import java.{ util ⇒ ju }
import akka.stream.impl.MaterializerSession.MaterializationPanic
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource
import akka.stream.scaladsl.Keep
import akka.stream._
import org.reactivestreams.{ Processor, Subscription, Publisher, Subscriber }
import scala.util.control.{ NoStackTrace, NonFatal }
import akka.event.Logging.simpleName
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicLong
import scala.collection.JavaConverters._
import akka.stream.impl.fusing.GraphStageModule
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource
import akka.stream.impl.fusing.GraphModule

/**
 * INTERNAL API
 */
object StreamLayout {

  // compile-time constant
  final val Debug = false

  final def validate(m: Module, level: Int = 0, doPrint: Boolean = false, idMap: ju.Map[AnyRef, Integer] = new ju.HashMap): Unit = {
    val ids = Iterator from 1
    def id(obj: AnyRef) = idMap get obj match {
      case null ⇒
        val x = ids.next()
        idMap.put(obj, x)
        x
      case x ⇒ x
    }
    def in(i: InPort) = s"${i.toString}@${id(i)}"
    def out(o: OutPort) = s"${o.toString}@${id(o)}"
    def ins(i: Iterable[InPort]) = i.map(in).mkString("In[", ",", "]")
    def outs(o: Iterable[OutPort]) = o.map(out).mkString("Out[", ",", "]")
    def pair(p: (OutPort, InPort)) = s"${in(p._2)}->${out(p._1)}"
    def pairs(p: Iterable[(OutPort, InPort)]) = p.map(pair).mkString("[", ",", "]")

    import m._

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
        case ((ai, di, ao, doo), sm) ⇒
          (ai ++ sm.inPorts, di ++ ai.intersect(sm.inPorts), ao ++ sm.outPorts, doo ++ ao.intersect(sm.outPorts))
      }
    if (dupIn.nonEmpty) problems ::= s"duplicate ports in submodules ${ins(dupIn)}"
    if (dupOut.nonEmpty) problems ::= s"duplicate ports in submodules ${outs(dupOut)}"
    if (!isSealed && (inset -- allIn).nonEmpty) problems ::= s"foreign inlets ${ins(inset -- allIn)}"
    if (!isSealed && (outset -- allOut).nonEmpty) problems ::= s"foreign outlets ${outs(outset -- allOut)}"
    val unIn = allIn -- inset -- upstreams.keySet
    if (unIn.nonEmpty && !isCopied) problems ::= s"unconnected inlets ${ins(unIn)}"
    val unOut = allOut -- outset -- downstreams.keySet
    if (unOut.nonEmpty && !isCopied) problems ::= s"unconnected outlets ${outs(unOut)}"

    def atomics(n: MaterializedValueNode): Set[Module] =
      n match {
        case Ignore                  ⇒ Set.empty
        case Transform(f, dep)       ⇒ atomics(dep)
        case Atomic(module)          ⇒ Set(module)
        case Combine(f, left, right) ⇒ atomics(left) ++ atomics(right)
      }
    val atomic = atomics(materializedValueComputation)
    val graphValues = subModules.flatMap {
      case GraphModule(_, _, _, mvids) ⇒ mvids
      case _                           ⇒ Nil
    }
    if ((atomic -- subModules -- graphValues - m).nonEmpty)
      problems ::= s"computation refers to non-existent modules [${atomic -- subModules -- graphValues - m mkString ","}]"

    val print = doPrint || problems.nonEmpty

    if (print) {
      val indent = " " * (level * 2)
      println(s"$indent${simpleName(this)}($shape): ${ins(inPorts)} ${outs(outPorts)}")
      downstreams foreach { case (o, i) ⇒ println(s"$indent    ${out(o)} -> ${in(i)}") }
      problems foreach (p ⇒ println(s"$indent  -!- $p"))
    }

    subModules foreach (sm ⇒ validate(sm, level + 1, print, idMap))

    if (problems.nonEmpty && !doPrint) throw new IllegalStateException(s"module inconsistent, found ${problems.size} problems")
  }

  // TODO: Materialization order
  // TODO: Special case linear composites
  // TODO: Cycles

  sealed trait MaterializedValueNode {
    /*
     * These nodes are used in hash maps and therefore must have efficient implementations
     * of hashCode and equals. There is no value in allowing aliases to be equal, so using
     * reference equality.
     */
    override def hashCode: Int = super.hashCode
    override def equals(other: Any): Boolean = super.equals(other)
  }
  case class Combine(f: (Any, Any) ⇒ Any, dep1: MaterializedValueNode, dep2: MaterializedValueNode) extends MaterializedValueNode {
    override def toString: String = s"Combine($dep1,$dep2)"
  }
  case class Atomic(module: Module) extends MaterializedValueNode {
    override def toString: String = s"Atomic(${module.attributes.nameOrDefault(module.getClass.getName)}[${module.hashCode}])"
  }
  case class Transform(f: Any ⇒ Any, dep: MaterializedValueNode) extends MaterializedValueNode {
    override def toString: String = s"Transform($dep)"
  }
  case object Ignore extends MaterializedValueNode

  trait Module {

    def shape: Shape
    /**
     * Verify that the given Shape has the same ports and return a new module with that shape.
     * Concrete implementations may throw UnsupportedOperationException where applicable.
     */
    def replaceShape(s: Shape): Module

    final lazy val inPorts: Set[InPort] = shape.inlets.toSet
    final lazy val outPorts: Set[OutPort] = shape.outlets.toSet

    def isRunnable: Boolean = inPorts.isEmpty && outPorts.isEmpty
    final def isSink: Boolean = (inPorts.size == 1) && outPorts.isEmpty
    final def isSource: Boolean = (outPorts.size == 1) && inPorts.isEmpty
    final def isFlow: Boolean = (inPorts.size == 1) && (outPorts.size == 1)
    final def isBidiFlow: Boolean = (inPorts.size == 2) && (outPorts.size == 2)
    def isAtomic: Boolean = subModules.isEmpty
    def isCopied: Boolean = false
    def isFused: Boolean = false

    /**
     * Fuses this Module to `that` Module by wiring together `from` and `to`,
     * retaining the materialized value of `this` in the result
     * @param that a Module to fuse with
     * @param from the data source to wire
     * @param to the data sink to wire
     * @return a Module representing the fusion of `this` and `that`
     */
    final def fuse(that: Module, from: OutPort, to: InPort): Module =
      fuse(that, from, to, Keep.left)

    /**
     * Fuses this Module to `that` Module by wiring together `from` and `to`,
     * transforming the materialized values of `this` and `that` using the
     * provided function `f`
     * @param that a Module to fuse with
     * @param from the data source to wire
     * @param to the data sink to wire
     * @param f the function to apply to the materialized values
     * @return a Module representing the fusion of `this` and `that`
     */
    final def fuse[A, B, C](that: Module, from: OutPort, to: InPort, f: (A, B) ⇒ C): Module =
      this.compose(that, f).wire(from, to)

    /**
     * Creates a new Module based on the current Module but with
     * the given OutPort wired to the given InPort.
     *
     * @param from the OutPort to wire
     * @param to the InPort to wire
     * @return a new Module with the ports wired
     */
    final def wire(from: OutPort, to: InPort): Module = {
      if (Debug) validate(this)

      require(outPorts(from),
        if (downstreams.contains(from)) s"The output port [$from] is already connected"
        else s"The output port [$from] is not part of the underlying graph.")
      require(inPorts(to),
        if (upstreams.contains(to)) s"The input port [$to] is already connected"
        else s"The input port [$to] is not part of the underlying graph.")

      CompositeModule(
        if (isSealed) Set(this) else subModules,
        AmorphousShape(shape.inlets.filterNot(_ == to), shape.outlets.filterNot(_ == from)),
        downstreams.updated(from, to),
        upstreams.updated(to, from),
        materializedValueComputation,
        attributes)
    }

    final def transformMaterializedValue(f: Any ⇒ Any): Module = {
      if (Debug) validate(this)

      CompositeModule(
        if (this.isSealed) Set(this) else this.subModules,
        shape,
        downstreams,
        upstreams,
        Transform(f, if (this.isSealed) Atomic(this) else this.materializedValueComputation),
        if (this.isSealed) Attributes.none else attributes)
    }

    /**
     * Creates a new Module which is `this` Module composed with `that` Module.
     *
     * @param that a Module to be composed with (cannot be itself)
     * @return a Module that represents the composition of `this` and `that`
     */
    def compose(that: Module): Module = compose(that, Keep.left)

    /**
     * Creates a new Module which is `this` Module composed with `that` Module,
     * using the given function `f` to compose the materialized value of `this` with
     * the materialized value of `that`.
     * @param that a Module to be composed with (cannot be itself)
     * @param f a function which combines the materialized values
     * @tparam A the type of the materialized value of `this`
     * @tparam B the type of the materialized value of `that`
     * @tparam C the type of the materialized value of the returned Module
     * @return a Module that represents the composition of `this` and `that`
     */
    def compose[A, B, C](that: Module, f: (A, B) ⇒ C): Module = {
      if (Debug) validate(this)

      require(that ne this, "A module cannot be added to itself. You should pass a separate instance to compose().")
      require(!subModules(that), "An existing submodule cannot be added again. All contained modules must be unique.")

      val modules1 = if (this.isSealed) Set(this) else this.subModules
      val modules2 = if (that.isSealed) Set(that) else that.subModules

      val matComputation1 = if (this.isSealed) Atomic(this) else this.materializedValueComputation
      val matComputation2 = if (that.isSealed) Atomic(that) else that.materializedValueComputation

      CompositeModule(
        modules1 ++ modules2,
        AmorphousShape(shape.inlets ++ that.shape.inlets, shape.outlets ++ that.shape.outlets),
        downstreams ++ that.downstreams,
        upstreams ++ that.upstreams,
        // would like to optimize away this allocation for Keep.{left,right} but that breaks side-effecting transformations
        Combine(f.asInstanceOf[(Any, Any) ⇒ Any], matComputation1, matComputation2),
        Attributes.none)
    }

    /**
     * Creates a new Module which is `this` Module composed with `that` Module.
     *
     * The difference to compose(that) is that this version completely ignores the materialized value
     * computation of `that` while the normal version executes the computation and discards its result.
     * This means that this version must not be used for user-provided `that` modules because users may
     * transform materialized values only to achieve some side-effect; it can only be
     * used where we know that there is no meaningful computation to be done (like for
     * MaterializedValueSource).
     *
     * @param that a Module to be composed with (cannot be itself)
     * @return a Module that represents the composition of `this` and `that`
     */
    def composeNoMat(that: Module): Module = {
      if (Debug) validate(this)

      require(that ne this, "A module cannot be added to itself. You should pass a separate instance to compose().")
      require(!subModules(that), "An existing submodule cannot be added again. All contained modules must be unique.")

      val modules1 = if (this.isSealed) Set(this) else this.subModules
      val modules2 = if (that.isSealed) Set(that) else that.subModules

      val matComputation = if (this.isSealed) Atomic(this) else this.materializedValueComputation

      CompositeModule(
        modules1 ++ modules2,
        AmorphousShape(shape.inlets ++ that.shape.inlets, shape.outlets ++ that.shape.outlets),
        downstreams ++ that.downstreams,
        upstreams ++ that.upstreams,
        // would like to optimize away this allocation for Keep.{left,right} but that breaks side-effecting transformations
        matComputation,
        Attributes.none)
    }

    /**
     * Creates a new Module which contains `this` Module
     * @return a new Module
     */
    def nest(): Module = {
      if (Debug) validate(this)

      CompositeModule(
        Set(this),
        shape,
        /*
         * Composite modules always maintain the flattened upstreams/downstreams map (i.e. they contain all the
         * layout information of all the nested modules). Copied modules break the nesting, scoping them to the
         * copied module. The MaterializerSession will take care of propagating the necessary Publishers and Subscribers
         * from the enclosed scope to the outer scope.
         */
        downstreams,
        upstreams,
        /*
         * Wrapping like this shields the outer module from the details of the
         * materialized value computation of its submodules.
         */
        Atomic(this),
        Attributes.none)
    }

    def subModules: Set[Module]
    final def isSealed: Boolean = isAtomic || isCopied || isFused

    def downstreams: Map[OutPort, InPort] = Map.empty
    def upstreams: Map[InPort, OutPort] = Map.empty

    def materializedValueComputation: MaterializedValueNode = Atomic(this)
    def carbonCopy: Module

    def attributes: Attributes
    def withAttributes(attributes: Attributes): Module

    final override def hashCode(): Int = super.hashCode()
    final override def equals(obj: scala.Any): Boolean = super.equals(obj)
  }

  object EmptyModule extends Module {
    override def shape = ClosedShape
    override def replaceShape(s: Shape) =
      if (s == ClosedShape) this
      else throw new UnsupportedOperationException("cannot replace the shape of the EmptyModule")

    override def compose(that: Module): Module = that

    override def compose[A, B, C](that: Module, f: (A, B) ⇒ C): Module =
      throw new UnsupportedOperationException("It is invalid to combine materialized value with EmptyModule")

    override def nest(): Module = this

    override def subModules: Set[Module] = Set.empty

    override def withAttributes(attributes: Attributes): Module =
      throw new UnsupportedOperationException("EmptyModule cannot carry attributes")
    override def attributes = Attributes.none

    override def carbonCopy: Module = this

    override def isRunnable: Boolean = false
    override def isAtomic: Boolean = false
    override def materializedValueComputation: MaterializedValueNode = Ignore
  }

  final case class CopiedModule(override val shape: Shape,
                                override val attributes: Attributes,
                                copyOf: Module) extends Module {
    override val subModules: Set[Module] = Set(copyOf)

    override def withAttributes(attr: Attributes): Module = this.copy(attributes = attr)

    override def carbonCopy: Module = this.copy(shape = shape.deepCopy())

    override def replaceShape(s: Shape): Module = {
      shape.requireSamePortsAs(s)
      copy(shape = s)
    }

    override val materializedValueComputation: MaterializedValueNode = Atomic(copyOf)

    override def isCopied: Boolean = true

    override def toString: String = "copy of " + copyOf.toString
  }

  final case class CompositeModule(
    override val subModules: Set[Module],
    override val shape: Shape,
    override val downstreams: Map[OutPort, InPort],
    override val upstreams: Map[InPort, OutPort],
    override val materializedValueComputation: MaterializedValueNode,
    override val attributes: Attributes) extends Module {

    override def replaceShape(s: Shape): Module = {
      shape.requireSamePortsAs(s)
      copy(shape = s)
    }

    override def carbonCopy: Module = CopiedModule(shape.deepCopy(), attributes, copyOf = this)

    override def withAttributes(attributes: Attributes): Module = copy(attributes = attributes)

    override def toString =
      s"""
        | Module: ${this.attributes.nameOrDefault("unnamed")}
        | Modules: ${subModules.iterator.map(m ⇒ "\n   " + m.attributes.nameOrDefault(m.getClass.getName)).mkString("")}
        | Downstreams: ${downstreams.iterator.map { case (in, out) ⇒ s"\n   $in -> $out" }.mkString("")}
        | Upstreams: ${upstreams.iterator.map { case (out, in) ⇒ s"\n   $out -> $in" }.mkString("")}
        |""".stripMargin
  }

  final case class FusedModule(
    override val subModules: Set[Module],
    override val shape: Shape,
    override val downstreams: Map[OutPort, InPort],
    override val upstreams: Map[InPort, OutPort],
    override val materializedValueComputation: MaterializedValueNode,
    override val attributes: Attributes,
    info: Fusing.StructuralInfo) extends Module {

    override def isFused: Boolean = true

    override def replaceShape(s: Shape): Module = {
      shape.requireSamePortsAs(s)
      copy(shape = s)
    }

    override def carbonCopy: Module = CopiedModule(shape.deepCopy(), attributes, copyOf = this)

    override def withAttributes(attributes: Attributes): FusedModule = copy(attributes = attributes)

    override def toString =
      s"""
        |  Module: ${this.attributes.nameOrDefault("unnamed")}
        |  Modules:
        |    ${subModules.iterator.map(m ⇒ m.toString.split("\n").mkString("\n    ")).mkString("\n    ")}
        |  Downstreams: ${downstreams.iterator.map { case (in, out) ⇒ s"\n   $in -> $out" }.mkString("")}
        |  Upstreams: ${upstreams.iterator.map { case (out, in) ⇒ s"\n   $out -> $in" }.mkString("")}
        |""".stripMargin
  }
}

private[stream] object VirtualProcessor {
  sealed trait Termination
  case object Allowed extends Termination
  case object Completed extends Termination
  case class Failed(ex: Throwable) extends Termination

  private val InertSubscriber = new CancellingSubscriber[Any]
}

private[stream] final class VirtualProcessor[T] extends Processor[T, T] {
  import VirtualProcessor._
  import ReactiveStreamsCompliance._

  private val subscriptionStatus = new AtomicReference[AnyRef]
  private val terminationStatus = new AtomicReference[Termination]

  override def subscribe(s: Subscriber[_ >: T]): Unit = {
    requireNonNullSubscriber(s)
    if (subscriptionStatus.compareAndSet(null, s)) () // wait for onSubscribe
    else
      subscriptionStatus.get match {
        case sub: Subscriber[_] ⇒ rejectAdditionalSubscriber(s, "VirtualProcessor")
        case sub: Sub ⇒
          try {
            subscriptionStatus.set(s)
            tryOnSubscribe(s, sub)
            sub.closeLatch() // allow onNext only now
            terminationStatus.getAndSet(Allowed) match {
              case null                        ⇒ // nothing happened yet
              case VirtualProcessor.Completed  ⇒ tryOnComplete(s)
              case VirtualProcessor.Failed(ex) ⇒ tryOnError(s, ex)
              case VirtualProcessor.Allowed    ⇒ // all good
            }
          } catch {
            case NonFatal(ex) ⇒ sub.cancel()
          }
      }
  }

  override def onSubscribe(s: Subscription): Unit = {
    requireNonNullSubscription(s)
    val wrapped = new Sub(s)
    if (subscriptionStatus.compareAndSet(null, wrapped)) () // wait for Subscriber
    else
      subscriptionStatus.get match {
        case sub: Subscriber[_] ⇒
          terminationStatus.get match {
            case Allowed ⇒
              /*
               * There is a race condition here: if this thread reads the subscriptionStatus after
               * set set() in subscribe() but then sees the terminationStatus before the getAndSet()
               * is published then we will rely upon the downstream Subscriber for cancelling this
               * Subscription. I only mention this because the TCK requires that we handle this here
               * (since the manualSubscriber used there does not expose this behavior).
               */
              s.cancel()
            case _ ⇒
              tryOnSubscribe(sub, wrapped)
              wrapped.closeLatch() // allow onNext only now
              terminationStatus.set(Allowed)
          }
        case sub: Subscription ⇒
          s.cancel() // reject further Subscriptions
      }
  }

  override def onError(t: Throwable): Unit = {
    requireNonNullException(t)
    if (terminationStatus.compareAndSet(null, Failed(t))) () // let it be picked up by subscribe()
    else tryOnError(subscriptionStatus.get.asInstanceOf[Subscriber[T]], t)
  }

  override def onComplete(): Unit =
    if (terminationStatus.compareAndSet(null, Completed)) () // let it be picked up by subscribe()
    else tryOnComplete(subscriptionStatus.get.asInstanceOf[Subscriber[T]])

  override def onNext(t: T): Unit = {
    requireNonNullElement(t)
    tryOnNext(subscriptionStatus.get.asInstanceOf[Subscriber[T]], t)
  }

  private final class Sub(s: Subscription) extends AtomicLong with Subscription {
    override def cancel(): Unit = {
      subscriptionStatus.set(InertSubscriber)
      s.cancel()
    }
    @tailrec
    override def request(n: Long): Unit = {
      val current = get
      if (current < 0) s.request(n)
      else if (compareAndSet(current, current + n)) ()
      else request(n)
    }
    def closeLatch(): Unit = {
      val requested = getAndSet(-1)
      if (requested > 0) s.request(requested)
    }
  }
}

/**
 * INERNAL API
 */
private[stream] object MaterializerSession {
  class MaterializationPanic(cause: Throwable) extends RuntimeException("Materialization aborted.", cause) with NoStackTrace

  final val Debug = false
}

/**
 * INTERNAL API
 */
private[stream] abstract class MaterializerSession(val topLevel: StreamLayout.Module, val initialAttributes: Attributes) {
  import StreamLayout._

  private var subscribersStack: List[ju.Map[InPort, Subscriber[Any]]] =
    new ju.HashMap[InPort, Subscriber[Any]] :: Nil
  private var publishersStack: List[ju.Map[OutPort, Publisher[Any]]] =
    new ju.HashMap[OutPort, Publisher[Any]] :: Nil

  /*
   * Please note that this stack keeps track of the scoped modules wrapped in CopiedModule but not the CopiedModule
   * itself. The reason is that the CopiedModule itself is only needed for the enterScope and exitScope methods but
   * not elsewhere. For this reason they are just simply passed as parameters to those methods.
   *
   * The reason why the encapsulated (copied) modules are stored as mutable state to save subclasses of this class
   * from passing the current scope around or even knowing about it.
   */
  private var moduleStack: List[Module] = topLevel :: Nil

  private def subscribers: ju.Map[InPort, Subscriber[Any]] = subscribersStack.head
  private def publishers: ju.Map[OutPort, Publisher[Any]] = publishersStack.head
  private def currentLayout: Module = moduleStack.head

  // Enters a copied module and establishes a scope that prevents internals to leak out and interfere with copies
  // of the same module.
  // We don't store the enclosing CopiedModule itself as state since we don't use it anywhere else than exit and enter
  private def enterScope(enclosing: CopiedModule): Unit = {
    subscribersStack ::= new ju.HashMap
    publishersStack ::= new ju.HashMap
    moduleStack ::= enclosing.copyOf
  }

  // Exits the scope of the copied module and propagates Publishers/Subscribers to the enclosing scope assigning
  // them to the copied ports instead of the original ones (since there might be multiple copies of the same module
  // leading to port identity collisions)
  // We don't store the enclosing CopiedModule itself as state since we don't use it anywhere else than exit and enter
  private def exitScope(enclosing: CopiedModule): Unit = {
    val scopeSubscribers = subscribers
    val scopePublishers = publishers
    subscribersStack = subscribersStack.tail
    publishersStack = publishersStack.tail
    moduleStack = moduleStack.tail

    // When we exit the scope of a copied module,  pick up the Subscribers/Publishers belonging to exposed ports of
    // the original module and assign them to the copy ports in the outer scope that we will return to
    enclosing.copyOf.shape.inlets.iterator.zip(enclosing.shape.inlets.iterator).foreach {
      case (original, exposed) ⇒ assignPort(exposed, scopeSubscribers.get(original))
    }

    enclosing.copyOf.shape.outlets.iterator.zip(enclosing.shape.outlets.iterator).foreach {
      case (original, exposed) ⇒ assignPort(exposed, scopePublishers.get(original))
    }
  }

  final def materialize(): Any = {
    require(topLevel ne EmptyModule, "An empty module cannot be materialized (EmptyModule was given)")
    require(
      topLevel.isRunnable,
      s"The top level module cannot be materialized because it has unconnected ports: ${(topLevel.inPorts ++ topLevel.outPorts).mkString(", ")}")
    try materializeModule(topLevel, initialAttributes and topLevel.attributes)
    catch {
      case NonFatal(cause) ⇒
        // PANIC!!! THE END OF THE MATERIALIZATION IS NEAR!
        // Cancels all intermediate Publishers and fails all intermediate Subscribers.
        // (This is an attempt to clean up after an exception during materialization)
        val errorPublisher = new ErrorPublisher(new MaterializationPanic(cause), "")
        for (subMap ← subscribersStack; sub ← subMap.asScala.valuesIterator)
          errorPublisher.subscribe(sub)

        for (pubMap ← publishersStack; pub ← pubMap.asScala.valuesIterator)
          pub.subscribe(new CancellingSubscriber)

        throw cause
    }
  }

  protected def mergeAttributes(parent: Attributes, current: Attributes): Attributes =
    parent and current

  private val matValSrc: ju.Map[MaterializedValueNode, List[MaterializedValueSource[Any]]] = new ju.HashMap
  def registerSrc(ms: MaterializedValueSource[Any]): Unit = {
    if (MaterializerSession.Debug) println(s"registering source $ms")
    matValSrc.get(ms.computation) match {
      case null ⇒ matValSrc.put(ms.computation, ms :: Nil)
      case xs   ⇒ matValSrc.put(ms.computation, ms :: xs)
    }
  }

  protected def materializeModule(module: Module, effectiveAttributes: Attributes): Any = {
    val materializedValues: ju.Map[Module, Any] = new ju.HashMap

    for (submodule ← module.subModules) {
      val subEffectiveAttributes = mergeAttributes(effectiveAttributes, submodule.attributes)
      submodule match {
        case GraphStageModule(shape, attributes, mv: MaterializedValueSource[_]) ⇒
          val copy = mv.copySrc.asInstanceOf[MaterializedValueSource[Any]]
          registerSrc(copy)
          materializeAtomic(copy.module, subEffectiveAttributes, materializedValues)
        case atomic if atomic.isAtomic ⇒
          materializeAtomic(atomic, subEffectiveAttributes, materializedValues)
        case copied: CopiedModule ⇒
          enterScope(copied)
          materializedValues.put(copied, materializeModule(copied, subEffectiveAttributes))
          exitScope(copied)
        case composite ⇒
          materializedValues.put(composite, materializeComposite(composite, subEffectiveAttributes))
      }
    }

    if (MaterializerSession.Debug) {
      println("RESOLVING")
      println(s"  module = $module")
      println(s"  computation = ${module.materializedValueComputation}")
      println(s"  matValSrc = $matValSrc")
      println(s"  matVals = $materializedValues")
    }
    resolveMaterialized(module.materializedValueComputation, materializedValues, "  ")
  }

  protected def materializeComposite(composite: Module, effectiveAttributes: Attributes): Any = {
    materializeModule(composite, effectiveAttributes)
  }

  protected def materializeAtomic(atomic: Module, effectiveAttributes: Attributes, matVal: ju.Map[Module, Any]): Unit

  private def resolveMaterialized(matNode: MaterializedValueNode, matVal: ju.Map[Module, Any], indent: String): Any = {
    if (MaterializerSession.Debug) println(indent + matNode)
    val ret = matNode match {
      case Atomic(m)          ⇒ matVal.get(m)
      case Combine(f, d1, d2) ⇒ f(resolveMaterialized(d1, matVal, indent + "  "), resolveMaterialized(d2, matVal, indent + "  "))
      case Transform(f, d)    ⇒ f(resolveMaterialized(d, matVal, indent + "  "))
      case Ignore             ⇒ ()
    }
    if (MaterializerSession.Debug) println(indent + s"result = $ret")
    matValSrc.remove(matNode) match {
      case null ⇒ // nothing to do
      case srcs ⇒
        if (MaterializerSession.Debug) println(indent + s"triggering sources $srcs")
        srcs.foreach(_.setValue(ret))
    }
    ret
  }

  final protected def assignPort(in: InPort, subscriber: Subscriber[Any]): Unit = {
    subscribers.put(in, subscriber)
    // Interface (unconnected) ports of the current scope will be wired when exiting the scope
    if (!currentLayout.inPorts(in)) {
      val publisher = publishers.get(currentLayout.upstreams(in))
      if (publisher ne null) publisher.subscribe(subscriber)
    }
  }

  final protected def assignPort(out: OutPort, publisher: Publisher[Any]): Unit = {
    publishers.put(out, publisher)
    // Interface (unconnected) ports of the current scope will be wired when exiting the scope
    if (!currentLayout.outPorts(out)) {
      val subscriber = subscribers.get(currentLayout.downstreams(out))
      if (subscriber ne null) publisher.subscribe(subscriber)
    }
  }

}
