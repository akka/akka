/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference
import java.{ util ⇒ ju }

import akka.NotUsed
import akka.event.Logging
import akka.event.Logging.simpleName
import akka.stream._
import akka.stream.impl.MaterializerSession.MaterializationPanic
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.fusing.GraphModule
import akka.stream.impl.fusing.GraphStages.MaterializedValueSource
import akka.stream.scaladsl.Keep
import org.reactivestreams.{ Processor, Publisher, Subscriber, Subscription }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.control.{ NoStackTrace, NonFatal }

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
    if (inset != inPorts) problems ::= s"shape has extra ${ins(inset diff inPorts)}, module has extra ${ins(inPorts diff inset)}"
    if (inset.intersect(upstreams.keySet).nonEmpty) problems ::= s"found connected inlets ${inset.intersect(upstreams.keySet)}"
    if (outset.size != shape.outlets.size) problems ::= "shape has duplicate outlets: " + outs(shape.outlets)
    if (outset != outPorts) problems ::= s"shape has extra ${outs(outset diff outPorts)}, module has extra ${outs(outPorts diff outset)}"
    if (outset.intersect(downstreams.keySet).nonEmpty) problems ::= s"found connected outlets ${outset.intersect(downstreams.keySet)}"
    val ups = upstreams.toSet
    val ups2 = ups.map(_.swap)
    val downs = downstreams.toSet
    val inter = ups2.intersect(downs)
    if (downs != ups2) problems ::= s"inconsistent maps: ups ${pairs(ups2 diff inter)} downs ${pairs(downs diff inter)}"
    val (allIn, dupIn, allOut, dupOut) =
      subModules.foldLeft((Set.empty[InPort], Set.empty[InPort], Set.empty[OutPort], Set.empty[OutPort])) {
        case ((ai, di, ao, doo), sm) ⇒
          (ai ++ sm.inPorts, di ++ ai.intersect(sm.inPorts), ao ++ sm.outPorts, doo ++ ao.intersect(sm.outPorts))
      }
    if (dupIn.nonEmpty) problems ::= s"duplicate ports in submodules ${ins(dupIn)}"
    if (dupOut.nonEmpty) problems ::= s"duplicate ports in submodules ${outs(dupOut)}"
    if (!isSealed && (inset diff allIn).nonEmpty) problems ::= s"foreign inlets ${ins(inset diff allIn)}"
    if (!isSealed && (outset diff allOut).nonEmpty) problems ::= s"foreign outlets ${outs(outset diff allOut)}"
    val unIn = allIn diff inset diff upstreams.keySet
    if (unIn.nonEmpty && !isCopied) problems ::= s"unconnected inlets ${ins(unIn)}"
    val unOut = allOut diff outset diff downstreams.keySet
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
    if (((atomic diff subModules diff graphValues) - m).nonEmpty)
      problems ::= s"computation refers to non-existent modules [${(atomic diff subModules diff graphValues) - m mkString ","}]"

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

  object IgnorableMatValComp {
    def apply(comp: MaterializedValueNode): Boolean =
      comp match {
        case Atomic(module)            ⇒ IgnorableMatValComp(module)
        case _: Combine | _: Transform ⇒ false
        case Ignore                    ⇒ true
      }
    def apply(module: Module): Boolean =
      module match {
        case _: AtomicModule | EmptyModule        ⇒ true
        case CopiedModule(_, _, module)           ⇒ IgnorableMatValComp(module)
        case CompositeModule(_, _, _, _, comp, _) ⇒ IgnorableMatValComp(comp)
        case FusedModule(_, _, _, _, comp, _, _)  ⇒ IgnorableMatValComp(comp)
      }
  }

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
    override def toString: String = f"Atomic(${module.attributes.nameOrDefault(module.getClass.getName)}[${System.identityHashCode(module)}%08x])"
  }
  case class Transform(f: Any ⇒ Any, dep: MaterializedValueNode) extends MaterializedValueNode {
    override def toString: String = s"Transform($dep)"
  }
  case object Ignore extends MaterializedValueNode

  sealed trait Module {

    def shape: Shape
    /**
     * Verify that the given Shape has the same ports and return a new module with that shape.
     * Concrete implementations may throw UnsupportedOperationException where applicable.
     *
     * Please note that this method MUST NOT be implemented using a CopiedModule since
     * the purpose of replaceShape can also be to rearrange the ports (as in BidiFlow.reversed)
     * and that purpose would be defeated.
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
     *
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
     *
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

      require(
        outPorts(from),
        if (downstreams.contains(from)) s"The output port [$from] is already connected"
        else s"The output port [$from] is not part of the underlying graph.")
      require(
        inPorts(to),
        if (upstreams.contains(to)) s"The input port [$to] is already connected"
        else s"The input port [$to] is not part of the underlying graph.")

      CompositeModule(
        if (isSealed) Set(this) else subModules,
        AmorphousShape(shape.inlets.filterNot(_ == to), shape.outlets.filterNot(_ == from)),
        downstreams.updated(from, to),
        upstreams.updated(to, from),
        materializedValueComputation,
        if (isSealed) Attributes.none else attributes)
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
     *
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

      val modulesLeft = if (this.isSealed) Set(this) else this.subModules
      val modulesRight = if (that.isSealed) Set(that) else that.subModules

      val matCompLeft = if (this.isSealed) Atomic(this) else this.materializedValueComputation
      val matCompRight = if (that.isSealed) Atomic(that) else that.materializedValueComputation

      val mat =
        {
          val comp =
            if (f == scaladsl.Keep.left) {
              if (IgnorableMatValComp(matCompRight)) matCompLeft else null
            } else if (f == scaladsl.Keep.right) {
              if (IgnorableMatValComp(matCompLeft)) matCompRight else null
            } else null
          if (comp == null) Combine(f.asInstanceOf[(Any, Any) ⇒ Any], matCompLeft, matCompRight)
          else comp
        }

      CompositeModule(
        modulesLeft union modulesRight,
        AmorphousShape(shape.inlets ++ that.shape.inlets, shape.outlets ++ that.shape.outlets),
        downstreams ++ that.downstreams,
        upstreams ++ that.upstreams,
        mat,
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

    def subModules: Set[Module]
    final def isSealed: Boolean = isAtomic || isCopied || isFused || attributes.attributeList.nonEmpty

    def downstreams: Map[OutPort, InPort] = Map.empty
    def upstreams: Map[InPort, OutPort] = Map.empty

    def materializedValueComputation: MaterializedValueNode = Atomic(this)

    /**
     * The purpose of this method is to create a copy to be included in a larger
     * graph such that port identity clashes are avoided. Where a full copy is not
     * possible or desirable, use a CopiedModule. The shape of the resulting
     * module MUST NOT contain the same ports as this module’s shape.
     */
    def carbonCopy: Module

    def attributes: Attributes
    def withAttributes(attributes: Attributes): Module

    final override def hashCode(): Int = super.hashCode()
    final override def equals(obj: scala.Any): Boolean = super.equals(obj)
  }

  case object EmptyModule extends Module {
    override def shape = ClosedShape
    override def replaceShape(s: Shape) =
      if (s != shape) throw new UnsupportedOperationException("cannot replace the shape of the EmptyModule")
      else this

    override def compose(that: Module): Module = compose(that, scaladsl.Keep.left)

    override def compose[A, B, C](that: Module, f: (A, B) ⇒ C): Module = {
      if (f eq scaladsl.Keep.right) {
        that
      } else if (f eq scaladsl.Keep.left) {
        // If "that" has a fully ignorable materialized value, we ignore it, otherwise we keep the side effect and
        // explicitly map to NotUsed
        val mat =
          if (IgnorableMatValComp(that))
            Ignore
          else
            Transform(_ ⇒ NotUsed, that.materializedValueComputation)

        CompositeModule(
          if (that.isSealed) Set(that) else that.subModules,
          that.shape,
          that.downstreams,
          that.upstreams,
          mat,
          if (this.isSealed) Attributes.none else attributes)
      } else {
        throw new UnsupportedOperationException("It is invalid to combine materialized value with EmptyModule " +
          "except with Keep.left or Keep.right")
      }
    }

    override def withAttributes(attributes: Attributes): Module =
      throw new UnsupportedOperationException("EmptyModule cannot carry attributes")

    override def subModules: Set[Module] = Set.empty
    override def attributes = Attributes.none
    override def carbonCopy: Module = this
    override def isRunnable: Boolean = false
    override def isAtomic: Boolean = false
    override def materializedValueComputation: MaterializedValueNode = Ignore
  }

  final case class CopiedModule(
    override val shape:      Shape,
    override val attributes: Attributes,
    copyOf:                  Module) extends Module {
    override val subModules: Set[Module] = Set(copyOf)

    override def withAttributes(attr: Attributes): Module =
      if (attr ne attributes) this.copy(attributes = attr)
      else this

    override def carbonCopy: Module = this.copy(shape = shape.deepCopy())

    override def replaceShape(s: Shape): Module =
      if (s != shape) {
        shape.requireSamePortsAs(s)
        CompositeModule(this, s)
      } else this

    override val materializedValueComputation: MaterializedValueNode = Atomic(copyOf)

    override def isCopied: Boolean = true

    override def toString: String = f"[${System.identityHashCode(this)}%08x] copy of $copyOf"
  }

  final case class CompositeModule(
    override val subModules:                   Set[Module],
    override val shape:                        Shape,
    override val downstreams:                  Map[OutPort, InPort],
    override val upstreams:                    Map[InPort, OutPort],
    override val materializedValueComputation: MaterializedValueNode,
    override val attributes:                   Attributes) extends Module {

    override def replaceShape(s: Shape): Module =
      if (s != shape) {
        shape.requireSamePortsAs(s)
        copy(shape = s)
      } else this

    override def carbonCopy: Module = CopiedModule(shape.deepCopy(), attributes, copyOf = this)

    override def withAttributes(attributes: Attributes): Module = copy(attributes = attributes)

    override def toString =
      f"""CompositeModule [${System.identityHashCode(this)}%08x]
         |  Name: ${this.attributes.nameOrDefault("unnamed")}
         |  Modules:
         |    ${subModules.iterator.map(m ⇒ s"(${m.attributes.nameLifted.getOrElse("unnamed")}) ${m.toString.replaceAll("\n", "\n    ")}").mkString("\n    ")}
         |  Downstreams: ${downstreams.iterator.map { case (in, out) ⇒ s"\n    $in -> $out" }.mkString("")}
         |  Upstreams: ${upstreams.iterator.map { case (out, in) ⇒ s"\n    $out -> $in" }.mkString("")}
         |  MatValue: $materializedValueComputation""".stripMargin
  }

  object CompositeModule {
    def apply(m: Module, s: Shape): CompositeModule = CompositeModule(Set(m), s, Map.empty, Map.empty, Atomic(m), Attributes.none)
  }

  final case class FusedModule(
    override val subModules:                   Set[Module],
    override val shape:                        Shape,
    override val downstreams:                  Map[OutPort, InPort],
    override val upstreams:                    Map[InPort, OutPort],
    override val materializedValueComputation: MaterializedValueNode,
    override val attributes:                   Attributes,
    info:                                      Fusing.StructuralInfo) extends Module {

    override def isFused: Boolean = true

    override def replaceShape(s: Shape): Module =
      if (s != shape) {
        shape.requireSamePortsAs(s)
        copy(shape = s)
      } else this

    override def carbonCopy: Module = CopiedModule(shape.deepCopy(), attributes, copyOf = this)

    override def withAttributes(attributes: Attributes): FusedModule = copy(attributes = attributes)

    override def toString =
      f"""FusedModule [${System.identityHashCode(this)}%08x]
         |  Name: ${this.attributes.nameOrDefault("unnamed")}
         |  Modules:
         |    ${subModules.iterator.map(m ⇒ m.attributes.nameLifted.getOrElse(m.toString.replaceAll("\n", "\n    "))).mkString("\n    ")}
         |  Downstreams: ${downstreams.iterator.map { case (in, out) ⇒ s"\n    $in -> $out" }.mkString("")}
         |  Upstreams: ${upstreams.iterator.map { case (out, in) ⇒ s"\n    $out -> $in" }.mkString("")}
         |  MatValue: $materializedValueComputation""".stripMargin
  }

  /**
   * This is the only extension point for the sealed type hierarchy: composition
   * (i.e. the module tree) is managed strictly within this file, only leaf nodes
   * may be declared elsewhere.
   */
  abstract class AtomicModule extends Module {
    final override def subModules: Set[Module] = Set.empty
    final override def downstreams: Map[OutPort, InPort] = super.downstreams
    final override def upstreams: Map[InPort, OutPort] = super.upstreams
  }
}

/**
 * INTERNAL API
 */
private[stream] object VirtualProcessor {
  case object Inert {
    val subscriber = new CancellingSubscriber[Any]
  }
  case class Both(subscriber: Subscriber[Any])
  object Both {
    def create(s: Subscriber[_]) = Both(s.asInstanceOf[Subscriber[Any]])
  }
}

/**
 * INTERNAL API
 *
 * This is a transparent processor that shall consume as little resources as
 * possible. Due to the possibility of receiving uncoordinated inputs from both
 * downstream and upstream, this needs an atomic state machine which looks a
 * little like this:
 *
 *            +--------------+      (2)    +------------+
 *            |     null     | ----------> | Subscriber |
 *            +--------------+             +------------+
 *                   |                           |
 *               (1) |                           | (1)
 *                  \|/                         \|/
 *            +--------------+      (2)    +------------+ --\
 *            | Subscription | ----------> |    Both    |    | (4)
 *            +--------------+             +------------+ <-/
 *                   |                           |
 *               (3) |                           | (3)
 *                  \|/                         \|/
 *            +--------------+      (2)    +------------+ --\
 *            |   Publisher  | ----------> |   Inert    |    | (4, *)
 *            +--------------+             +------------+ <-/
 *
 * The idea is to keep the major state in only one atomic reference. The actions
 * that can happen are:
 *
 *  (1) onSubscribe
 *  (2) subscribe
 *  (3) onError / onComplete
 *  (4) onNext
 *      (*) Inert can be reached also by cancellation after which onNext is still fine
 *          so we just silently ignore possible spec violations here
 *
 * Any event that occurs in a state where no matching outgoing arrow can be found
 * is a spec violation, leading to the shutdown of this processor (meaning that
 * the state is updated such that all following actions match that of a failed
 * Publisher or a cancelling Subscriber, and the non-guilty party is informed if
 * already connected).
 *
 * request() can only be called after the Subscriber has received the Subscription
 * and that also means that onNext() will only happen after having transitioned into
 * the Both state as well. The Publisher state means that if the real
 * Publisher terminates before we get the Subscriber, we can just forget about the
 * real one and keep an already finished one around for the Subscriber.
 *
 * The Subscription that is offered to the Subscriber must cancel the original
 * Publisher if things go wrong (like `request(0)` coming in from downstream) and
 * it must ensure that we drop the Subscriber reference when `cancel` is invoked.
 */
private[stream] final class VirtualProcessor[T] extends AtomicReference[AnyRef] with Processor[T, T] {
  import ReactiveStreamsCompliance._
  import VirtualProcessor._

  override def subscribe(s: Subscriber[_ >: T]): Unit = {
    @tailrec def rec(sub: Subscriber[Any]): Unit =
      get() match {
        case null ⇒ if (!compareAndSet(null, s)) rec(sub)
        case subscription: Subscription ⇒
          if (compareAndSet(subscription, Both(sub))) establishSubscription(sub, subscription)
          else rec(sub)
        case pub: Publisher[_] ⇒
          if (compareAndSet(pub, Inert)) pub.subscribe(sub)
          else rec(sub)
        case _ ⇒
          rejectAdditionalSubscriber(sub, "VirtualProcessor")
      }

    if (s == null) {
      val ex = subscriberMustNotBeNullException
      try rec(Inert.subscriber)
      finally throw ex // must throw NPE, rule 2:13
    } else rec(s.asInstanceOf[Subscriber[Any]])
  }

  override final def onSubscribe(s: Subscription): Unit = {
    @tailrec def rec(obj: AnyRef): Unit =
      get() match {
        case null ⇒ if (!compareAndSet(null, obj)) rec(obj)
        case subscriber: Subscriber[_] ⇒
          obj match {
            case subscription: Subscription ⇒
              if (compareAndSet(subscriber, Both.create(subscriber))) establishSubscription(subscriber, subscription)
              else rec(obj)
            case pub: Publisher[_] ⇒
              getAndSet(Inert) match {
                case Inert ⇒ // nothing to be done
                case _     ⇒ pub.subscribe(subscriber.asInstanceOf[Subscriber[Any]])
              }
          }
        case _ ⇒
          // spec violation
          tryCancel(s)
      }

    if (s == null) {
      val ex = subscriptionMustNotBeNullException
      try rec(ErrorPublisher(ex, "failed-VirtualProcessor"))
      finally throw ex // must throw NPE, rule 2:13
    } else rec(s)
  }

  private def establishSubscription(subscriber: Subscriber[_], subscription: Subscription): Unit = {
    val wrapped = new WrappedSubscription(subscription)
    try {
      subscriber.onSubscribe(wrapped)
      // Requests will be only allowed once onSubscribe has returned to avoid reentering on an onNext before
      // onSubscribe completed
      wrapped.ungateDemandAndRequestBuffered()
    } catch {
      case NonFatal(ex) ⇒
        set(Inert)
        tryCancel(subscription)
        tryOnError(subscriber, ex)
    }
  }

  override def onError(t: Throwable): Unit = {
    /*
     * `ex` is always a reasonable Throwable that we should communicate downstream,
     * but if `t` was `null` then the spec requires us to throw an NPE (which `ex`
     * will be in this case).
     */
    @tailrec def rec(ex: Throwable): Unit =
      get() match {
        case null ⇒
          if (!compareAndSet(null, ErrorPublisher(ex, "failed-VirtualProcessor"))) rec(ex)
          else if (t == null) throw ex
        case s: Subscription ⇒
          if (!compareAndSet(s, ErrorPublisher(ex, "failed-VirtualProcessor"))) rec(ex)
          else if (t == null) throw ex
        case Both(s) ⇒
          set(Inert)
          try tryOnError(s, ex)
          finally if (t == null) throw ex // must throw NPE, rule 2:13
        case s: Subscriber[_] ⇒ // spec violation
          getAndSet(Inert) match {
            case Inert ⇒ // nothing to be done
            case _     ⇒ ErrorPublisher(ex, "failed-VirtualProcessor").subscribe(s)
          }
        case _ ⇒ // spec violation or cancellation race, but nothing we can do
      }

    val ex = if (t == null) exceptionMustNotBeNullException else t
    rec(ex)
  }

  @tailrec override final def onComplete(): Unit =
    get() match {
      case null            ⇒ if (!compareAndSet(null, EmptyPublisher)) onComplete()
      case s: Subscription ⇒ if (!compareAndSet(s, EmptyPublisher)) onComplete()
      case Both(s) ⇒
        set(Inert)
        tryOnComplete(s)
      case s: Subscriber[_] ⇒ // spec violation
        set(Inert)
        EmptyPublisher.subscribe(s)
      case _ ⇒ // spec violation or cancellation race, but nothing we can do
    }

  override def onNext(t: T): Unit =
    if (t == null) {
      val ex = elementMustNotBeNullException
      @tailrec def rec(): Unit =
        get() match {
          case x @ (null | _: Subscription) ⇒ if (!compareAndSet(x, ErrorPublisher(ex, "failed-VirtualProcessor"))) rec()
          case s: Subscriber[_]             ⇒ try s.onError(ex) catch { case NonFatal(_) ⇒ } finally set(Inert)
          case Both(s)                      ⇒ try s.onError(ex) catch { case NonFatal(_) ⇒ } finally set(Inert)
          case _                            ⇒ // spec violation or cancellation race, but nothing we can do
        }
      rec()
      throw ex // must throw NPE, rule 2:13
    } else {
      @tailrec def rec(): Unit =
        get() match {
          case Both(s) ⇒
            try s.onNext(t)
            catch {
              case NonFatal(e) ⇒
                set(Inert)
                throw new IllegalStateException("Subscriber threw exception, this is in violation of rule 2:13", e)
            }
          case s: Subscriber[_] ⇒ // spec violation
            val ex = new IllegalStateException(noDemand)
            getAndSet(Inert) match {
              case Inert ⇒ // nothing to be done
              case _     ⇒ ErrorPublisher(ex, "failed-VirtualProcessor").subscribe(s)
            }
            throw ex
          case Inert | _: Publisher[_] ⇒ // nothing to be done
          case other ⇒
            val pub = ErrorPublisher(new IllegalStateException(noDemand), "failed-VirtualPublisher")
            if (!compareAndSet(other, pub)) rec()
            else throw pub.t
        }
      rec()
    }

  private def noDemand = "spec violation: onNext was signaled from upstream without demand"

  object WrappedSubscription {
    sealed trait SubscriptionState { def demand: Long }
    case object PassThrough extends SubscriptionState { override def demand: Long = 0 }
    final case class Buffering(demand: Long) extends SubscriptionState

    val NoBufferedDemand = Buffering(0)
  }

  // Extdending AtomicReference to make the hot memory location share the same cache line with the Subscription
  private class WrappedSubscription(real: Subscription)
    extends AtomicReference[WrappedSubscription.SubscriptionState](WrappedSubscription.NoBufferedDemand) with Subscription {
    import WrappedSubscription._

    // Release
    def ungateDemandAndRequestBuffered(): Unit = {
      // Ungate demand
      val requests = getAndSet(PassThrough).demand
      // And request buffered demand
      if (requests > 0) real.request(requests)
    }

    override def request(n: Long): Unit = {
      if (n < 1) {
        tryCancel(real)
        VirtualProcessor.this.getAndSet(Inert) match {
          case Both(s) ⇒ rejectDueToNonPositiveDemand(s)
          case Inert   ⇒ // another failure has won the race
          case _       ⇒ // this cannot possibly happen, but signaling errors is impossible at this point
        }
      } else {
        // NOTE: At this point, batched requests might not have been dispatched, i.e. this can reorder requests.
        // This does not violate the Spec though, since we are a "Processor" here and although we, in reality,
        // proxy downstream requests, it is virtually *us* that emit the requests here and we are free to follow
        // any pattern of emitting them.
        // The only invariant we need to keep is to never emit more requests than the downstream emitted so far.
        @tailrec def bufferDemand(n: Long): Unit = {
          val current = get()
          if (current eq PassThrough) real.request(n)
          else if (!compareAndSet(current, Buffering(current.demand + n))) bufferDemand(n)
        }
        bufferDemand(n)
      }
    }
    override def cancel(): Unit = {
      VirtualProcessor.this.set(Inert)
      real.cancel()
    }
  }
}

/**
 * INTERNAL API
 *
 * The implementation of `Sink.asPublisher` needs to offer a `Publisher` that
 * defers to the upstream that is connected during materialization. This would
 * be trivial if it were not for materialized value computations that may even
 * spawn the code that does `pub.subscribe(sub)` in a Future, running concurrently
 * with the actual materialization. Therefore we implement a minimial shell here
 * that plugs the downstream and the upstream together as soon as both are known.
 * Using a VirtualProcessor would technically also work, but it would defeat the
 * purpose of subscription timeouts—the subscription would always already be
 * established from the Actor’s perspective, regardless of whether a downstream
 * will ever be connected.
 *
 * One important consideration is that this `Publisher` must not retain a reference
 * to the `Subscriber` after having hooked it up with the real `Publisher`, hence
 * the use of `Inert.subscriber` as a tombstone.
 */
private[impl] class VirtualPublisher[T] extends AtomicReference[AnyRef] with Publisher[T] {
  import ReactiveStreamsCompliance._
  import VirtualProcessor.Inert

  override def subscribe(subscriber: Subscriber[_ >: T]): Unit = {
    requireNonNullSubscriber(subscriber)
    @tailrec def rec(): Unit = {
      get() match {
        case null ⇒ if (!compareAndSet(null, subscriber)) rec()
        case pub: Publisher[_] ⇒
          if (compareAndSet(pub, Inert.subscriber)) {
            pub.asInstanceOf[Publisher[T]].subscribe(subscriber)
          } else rec()
        case _: Subscriber[_] ⇒ rejectAdditionalSubscriber(subscriber, "Sink.asPublisher(fanout = false)")
      }
    }
    rec() // return value is boolean only to make the expressions above compile
  }

  @tailrec final def registerPublisher(pub: Publisher[_]): Unit =
    get() match {
      case null ⇒ if (!compareAndSet(null, pub)) registerPublisher(pub)
      case sub: Subscriber[r] ⇒
        set(Inert.subscriber)
        pub.asInstanceOf[Publisher[r]].subscribe(sub)
      case _ ⇒ throw new IllegalStateException("internal error")
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

  // the contained maps store either Subscriber[Any] or VirtualPublisher, but the type system cannot express that
  private var subscribersStack: List[ju.Map[InPort, AnyRef]] =
    new ju.HashMap[InPort, AnyRef] :: Nil
  private var publishersStack: List[ju.Map[OutPort, Publisher[Any]]] =
    new ju.HashMap[OutPort, Publisher[Any]] :: Nil
  private var matValSrcStack: List[ju.Map[MaterializedValueNode, List[MaterializedValueSource[Any]]]] =
    new ju.HashMap[MaterializedValueNode, List[MaterializedValueSource[Any]]] :: Nil

  /*
   * Please note that this stack keeps track of the scoped modules wrapped in CopiedModule but not the CopiedModule
   * itself. The reason is that the CopiedModule itself is only needed for the enterScope and exitScope methods but
   * not elsewhere. For this reason they are just simply passed as parameters to those methods.
   *
   * The reason why the encapsulated (copied) modules are stored as mutable state to save subclasses of this class
   * from passing the current scope around or even knowing about it.
   */
  private var moduleStack: List[Module] = topLevel :: Nil

  private def subscribers: ju.Map[InPort, AnyRef] = subscribersStack.head
  private def publishers: ju.Map[OutPort, Publisher[Any]] = publishersStack.head
  private def currentLayout: Module = moduleStack.head
  private def matValSrc: ju.Map[MaterializedValueNode, List[MaterializedValueSource[Any]]] = matValSrcStack.head

  // Enters a copied module and establishes a scope that prevents internals to leak out and interfere with copies
  // of the same module.
  // We don't store the enclosing CopiedModule itself as state since we don't use it anywhere else than exit and enter
  private def enterScope(enclosing: CopiedModule): Unit = {
    if (MaterializerSession.Debug) println(f"entering scope [${System.identityHashCode(enclosing)}%08x]")
    subscribersStack ::= new ju.HashMap
    publishersStack ::= new ju.HashMap
    matValSrcStack ::= new ju.HashMap
    moduleStack ::= enclosing.copyOf
  }

  // Exits the scope of the copied module and propagates Publishers/Subscribers to the enclosing scope assigning
  // them to the copied ports instead of the original ones (since there might be multiple copies of the same module
  // leading to port identity collisions)
  // We don't store the enclosing CopiedModule itself as state since we don't use it anywhere else than exit and enter
  private def exitScope(enclosing: CopiedModule): Unit = {
    if (MaterializerSession.Debug) println(f"exiting scope [${System.identityHashCode(enclosing)}%08x]")
    val scopeSubscribers = subscribers
    val scopePublishers = publishers
    subscribersStack = subscribersStack.tail
    publishersStack = publishersStack.tail
    matValSrcStack = matValSrcStack.tail
    moduleStack = moduleStack.tail

    if (MaterializerSession.Debug) println(s"  subscribers = $scopeSubscribers\n  publishers = $scopePublishers")

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
    if (MaterializerSession.Debug) println(s"beginning materialization of $topLevel")
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
          doSubscribe(errorPublisher, sub)

        for (pubMap ← publishersStack; pub ← pubMap.asScala.valuesIterator)
          pub.subscribe(new CancellingSubscriber)

        throw cause
    }
  }

  protected def mergeAttributes(parent: Attributes, current: Attributes): Attributes =
    parent and current

  def registerSrc(ms: MaterializedValueSource[Any]): Unit = {
    if (MaterializerSession.Debug) println(s"registering source $ms")
    matValSrc.get(ms.computation) match {
      case null ⇒ matValSrc.put(ms.computation, ms :: Nil)
      case xs   ⇒ matValSrc.put(ms.computation, ms :: xs)
    }
  }

  protected def materializeModule(module: Module, effectiveAttributes: Attributes): Any = {
    val materializedValues: ju.Map[Module, Any] = new ju.HashMap

    if (MaterializerSession.Debug) println(f"entering module [${System.identityHashCode(module)}%08x] (${Logging.simpleName(module)})")

    for (submodule ← module.subModules) {
      val subEffectiveAttributes = mergeAttributes(effectiveAttributes, submodule.attributes)
      submodule match {
        case atomic: AtomicModule ⇒
          materializeAtomic(atomic, subEffectiveAttributes, materializedValues)
        case copied: CopiedModule ⇒
          enterScope(copied)
          materializedValues.put(copied, materializeModule(copied, subEffectiveAttributes))
          exitScope(copied)
        case composite @ (_: CompositeModule | _: FusedModule) ⇒
          materializedValues.put(composite, materializeComposite(composite, subEffectiveAttributes))
        case EmptyModule ⇒ // nothing to do or say
      }
    }

    if (MaterializerSession.Debug) {
      println(f"resolving module [${System.identityHashCode(module)}%08x] computation ${module.materializedValueComputation}")
      println(s"  matValSrc = $matValSrc")
      println(s"  matVals =\n    ${materializedValues.asScala.map(p ⇒ "%08x".format(System.identityHashCode(p._1)) → p._2).mkString("\n    ")}")
    }

    val ret = resolveMaterialized(module.materializedValueComputation, materializedValues, 2)
    while (!matValSrc.isEmpty) {
      val node = matValSrc.keySet.iterator.next()
      if (MaterializerSession.Debug) println(s"  delayed computation of $node")
      resolveMaterialized(node, materializedValues, 4)
    }

    if (MaterializerSession.Debug) println(f"exiting module [${System.identityHashCode(module)}%08x]")

    ret
  }

  protected def materializeComposite(composite: Module, effectiveAttributes: Attributes): Any = {
    materializeModule(composite, effectiveAttributes)
  }

  protected def materializeAtomic(atomic: AtomicModule, effectiveAttributes: Attributes, matVal: ju.Map[Module, Any]): Unit

  private def resolveMaterialized(matNode: MaterializedValueNode, matVal: ju.Map[Module, Any], spaces: Int): Any = {
    if (MaterializerSession.Debug) println(" " * spaces + matNode)
    val ret = matNode match {
      case Atomic(m)          ⇒ matVal.get(m)
      case Combine(f, d1, d2) ⇒ f(resolveMaterialized(d1, matVal, spaces + 2), resolveMaterialized(d2, matVal, spaces + 2))
      case Transform(f, d)    ⇒ f(resolveMaterialized(d, matVal, spaces + 2))
      case Ignore             ⇒ NotUsed
    }
    if (MaterializerSession.Debug) println(" " * spaces + s"result = $ret")
    matValSrc.remove(matNode) match {
      case null ⇒ // nothing to do
      case srcs ⇒
        if (MaterializerSession.Debug) println(" " * spaces + s"triggering sources $srcs")
        srcs.foreach(_.setValue(ret))
    }
    ret
  }

  final protected def assignPort(in: InPort, subscriberOrVirtual: AnyRef): Unit = {
    subscribers.put(in, subscriberOrVirtual)

    currentLayout.upstreams.get(in) match {
      case Some(upstream) ⇒
        val publisher = publishers.get(upstream)
        if (publisher ne null) doSubscribe(publisher, subscriberOrVirtual)
      // Interface (unconnected) ports of the current scope will be wired when exiting the scope (or some parent scope)
      case None ⇒
    }
  }

  final protected def assignPort(out: OutPort, publisher: Publisher[Any]): Unit = {
    publishers.put(out, publisher)

    currentLayout.downstreams.get(out) match {
      case Some(downstream) ⇒
        val subscriber = subscribers.get(downstream)
        if (subscriber ne null) doSubscribe(publisher, subscriber)
      // Interface (unconnected) ports of the current scope will be wired when exiting the scope
      case None ⇒
    }
  }

  private def doSubscribe(publisher: Publisher[_ <: Any], subscriberOrVirtual: AnyRef): Unit =
    subscriberOrVirtual match {
      case s: Subscriber[_]       ⇒ publisher.subscribe(s.asInstanceOf[Subscriber[Any]])
      case v: VirtualPublisher[_] ⇒ v.registerPublisher(publisher)
    }

}

/**
 * INTERNAL API
 */
private[akka] final case class ProcessorModule[In, Out, Mat](
  val createProcessor: () ⇒ (Processor[In, Out], Mat),
  attributes:          Attributes                     = DefaultAttributes.processor) extends StreamLayout.AtomicModule {
  val inPort = Inlet[In]("ProcessorModule.in")
  val outPort = Outlet[Out]("ProcessorModule.out")
  override val shape = new FlowShape(inPort, outPort)

  override def replaceShape(s: Shape) = if (s != shape) throw new UnsupportedOperationException("cannot replace the shape of a FlowModule")
  else this

  override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  override def carbonCopy: Module = withAttributes(attributes)
  override def toString: String = f"ProcessorModule [${System.identityHashCode(this)}%08x]"
}
