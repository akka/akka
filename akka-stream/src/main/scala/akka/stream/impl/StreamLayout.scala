/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import akka.stream.impl.MaterializerSession.MaterializationPanic
import akka.stream.impl.StreamLayout.Module
import akka.stream.scaladsl.Keep
import akka.stream._
import org.reactivestreams.{ Processor, Subscription, Publisher, Subscriber }
import scala.collection.mutable
import scala.util.control.{ NoStackTrace, NonFatal }
import akka.event.Logging.simpleName
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicLong

/**
 * INTERNAL API
 */
private[akka] object StreamLayout {

  // compile-time constant
  final val Debug = false

  final def validate(m: Module, level: Int = 0, doPrint: Boolean = false, idMap: mutable.Map[AnyRef, Int] = mutable.Map.empty): Unit = {
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
    if ((atomic -- subModules - m).nonEmpty) problems ::= s"computation refers to non-existent modules [${atomic -- subModules - m mkString ","}]"

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

    final lazy val inPorts: Set[InPort] = shape.inlets.toSet
    final lazy val outPorts: Set[OutPort] = shape.outlets.toSet

    def isRunnable: Boolean = inPorts.isEmpty && outPorts.isEmpty
    final def isSink: Boolean = (inPorts.size == 1) && outPorts.isEmpty
    final def isSource: Boolean = (outPorts.size == 1) && inPorts.isEmpty
    final def isFlow: Boolean = (inPorts.size == 1) && (outPorts.size == 1)
    final def isBidiFlow: Boolean = (inPorts.size == 2) && (outPorts.size == 2)
    def isAtomic: Boolean = subModules.isEmpty
    def isCopied: Boolean = false

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
        subModules,
        AmorphousShape(shape.inlets.filterNot(_ == to), shape.outlets.filterNot(_ == from)),
        downstreams.updated(from, to),
        upstreams.updated(to, from),
        materializedValueComputation,
        attributes)
    }

    final def transformMaterializedValue(f: Any ⇒ Any): Module = {
      if (Debug) validate(this)

      CompositeModule(
        subModules = if (this.isSealed) Set(this) else this.subModules,
        shape,
        downstreams,
        upstreams,
        Transform(f, if (this.isSealed) Atomic(this) else this.materializedValueComputation),
        attributes)
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
        attributes)
    }

    /**
     * Creates a new Module which contains `this` Module
     * @return a new Module
     */
    def nest(): Module = {
      if (Debug) validate(this)

      CompositeModule(
        subModules = Set(this),
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
    final def isSealed: Boolean = isAtomic || isCopied

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

  final case class CopiedModule(shape: Shape, attributes: Attributes, copyOf: Module) extends Module {
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
    subModules: Set[Module],
    shape: Shape,
    override val downstreams: Map[OutPort, InPort],
    override val upstreams: Map[InPort, OutPort],
    override val materializedValueComputation: MaterializedValueNode,
    attributes: Attributes) extends Module {

    override def replaceShape(s: Shape): Module = {
      shape.requireSamePortsAs(s)
      copy(shape = s)
    }

    override def carbonCopy: Module = CopiedModule(shape.deepCopy(), attributes, copyOf = this)

    override def withAttributes(attributes: Attributes): Module = copy(attributes = attributes)

    override def toString =
      s"""
        | Module: ${this.attributes.nameOrDefault("unnamed")}
        | Modules: ${subModules.iterator.map(m ⇒ "   " + m.attributes.nameOrDefault(m.getClass.getName)).mkString("\n")}
        | Downstreams:
        | ${downstreams.iterator.map { case (in, out) ⇒ s"   $in -> $out" }.mkString("\n")}
        | Upstreams:
        | ${upstreams.iterator.map { case (out, in) ⇒ s"   $out -> $in" }.mkString("\n")}
      """.stripMargin
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
              case null       ⇒ // nothing happened yet
              case Completed  ⇒ tryOnComplete(s)
              case Failed(ex) ⇒ tryOnError(s, ex)
              case Allowed    ⇒ // all good
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
 * INTERNAL API
 */
private[stream] final case class MaterializedValueSource[M](
  shape: SourceShape[M] = SourceShape[M](Outlet[M]("Materialized.out")),
  attributes: Attributes = Attributes.name("Materialized")) extends StreamLayout.Module {

  override def subModules: Set[Module] = Set.empty
  override def withAttributes(attr: Attributes): Module = this.copy(shape = amendShape(attr), attributes = attr)
  override def carbonCopy: Module = this.copy(shape = SourceShape(Outlet[M]("Materialized.out")))

  override def replaceShape(s: Shape): Module =
    if (s == shape) this
    else throw new UnsupportedOperationException("cannot replace the shape of MaterializedValueSource")

  private def amendShape(attr: Attributes): SourceShape[M] = {
    val thisN = attributes.nameOrDefault(null)
    val thatN = attr.nameOrDefault(null)

    if ((thatN eq null) || thisN == thatN) shape
    else shape.copy(outlet = Outlet(thatN + ".out"))
  }
}

/**
 * INTERNAL API
 */
private[stream] object MaterializedValuePublisher {
  final val NotRequested = 0
  final val Requested = 1
  final val Completed = 2

  final val NoValue = new AnyRef
}

/**
 * INTERNAL API
 */
private[stream] class MaterializedValuePublisher extends Publisher[Any] {
  import MaterializedValuePublisher._

  private val value = new AtomicReference[AnyRef](NoValue)
  private val registeredSubscriber = new AtomicReference[Subscriber[_ >: Any]](null)
  private val requestState = new AtomicInteger(NotRequested)

  private def close(): Unit = {
    requestState.set(Completed)
    value.set(NoValue)
    registeredSubscriber.set(null)
  }

  private def tryOrClose(block: ⇒ Unit): Unit = {
    try block catch {
      case v: ReactiveStreamsCompliance.SpecViolation ⇒
        close()
      // What else can we do here?
      case NonFatal(e) ⇒
        val sub = registeredSubscriber.get()
        if ((sub ne null) &&
          requestState.compareAndSet(NotRequested, Completed) || requestState.compareAndSet(Requested, Completed)) {
          sub.onError(e)
        }
        close()
        throw e
    }
  }

  def setValue(m: Any): Unit =
    tryOrClose {
      if (value.compareAndSet(NoValue, m.asInstanceOf[AnyRef]) && requestState.get() == Requested)
        pushAndClose(m)
    }

  /*
   * Both call-sites do a CAS on their "own" side and a GET on the other side. The possible overlaps
   * are (removing symmetric cases where you can relabel A->B, B->A):
   *
   * A-CAS
   * A-GET
   * B-CAS
   * B-GET - pushAndClose fires here
   *
   * A-CAS
   * B-CAS
   * A-GET - pushAndClose fires here
   * B-GET - pushAndClose fires here
   *
   * A-CAS
   * B-CAS
   * B-GET - pushAndClose fires here
   * A-GET - pushAndClose fires here
   *
   * The proof that there are no other cases:
   *
   * - all permutations of 4 operations are 4! = 24
   * - the operations of A and B are cannot be reordered, so there are 24 / (2 * 2) = 6 actual orderings
   * - if we don't count cases which are a simple relabeling A->B, B->A, we get 6 / 2 = 3 reorderings
   *   which are all enumerated above.
   *
   * pushAndClose protects against double onNext by doing a CAS itself.
   */
  private def pushAndClose(m: Any): Unit = {
    if (requestState.compareAndSet(Requested, Completed)) {
      val sub = registeredSubscriber.get()
      ReactiveStreamsCompliance.tryOnNext(sub, m)
      ReactiveStreamsCompliance.tryOnComplete(sub)
      close()
    }
  }

  override def subscribe(subscriber: Subscriber[_ >: Any]): Unit = {
    tryOrClose {
      ReactiveStreamsCompliance.requireNonNullSubscriber(subscriber)
      if (registeredSubscriber.compareAndSet(null, subscriber)) {
        ReactiveStreamsCompliance.tryOnSubscribe(subscriber, new Subscription {
          override def cancel(): Unit = close()

          override def request(n: Long): Unit = {
            if (n <= 0) {
              ReactiveStreamsCompliance.tryOnError(
                subscriber,
                ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException)
            } else {
              if (requestState.compareAndSet(NotRequested, Requested)) {
                val m = value.get()
                if (m ne NoValue) pushAndClose(m)
              }
            }
          }
        })
      } else {
        if (subscriber == registeredSubscriber.get())
          ReactiveStreamsCompliance.rejectDuplicateSubscriber(subscriber)
        else
          ReactiveStreamsCompliance.rejectAdditionalSubscriber(subscriber, "MaterializedValuePublisher")
      }
    }
  }

}

/**
 * INERNAL API
 */
private[stream] object MaterializerSession {
  class MaterializationPanic(cause: Throwable) extends RuntimeException("Materialization aborted.", cause) with NoStackTrace
}

/**
 * INTERNAL API
 */
private[stream] abstract class MaterializerSession(val topLevel: StreamLayout.Module) {
  import StreamLayout._

  private var subscribersStack: List[mutable.Map[InPort, Subscriber[Any]]] =
    mutable.Map.empty[InPort, Subscriber[Any]].withDefaultValue(null) :: Nil
  private var publishersStack: List[mutable.Map[OutPort, Publisher[Any]]] =
    mutable.Map.empty[OutPort, Publisher[Any]].withDefaultValue(null) :: Nil

  /*
   * Please note that this stack keeps track of the scoped modules wrapped in CopiedModule but not the CopiedModule
   * itself. The reason is that the CopiedModule itself is only needed for the enterScope and exitScope methods but
   * not elsewhere. For this reason they are just simply passed as parameters to those methods.
   *
   * The reason why the encapsulated (copied) modules are stored as mutable state to save subclasses of this class
   * from passing the current scope around or even knowing about it.
   */
  private var moduleStack: List[Module] = topLevel :: Nil

  private def subscribers: mutable.Map[InPort, Subscriber[Any]] = subscribersStack.head
  private def publishers: mutable.Map[OutPort, Publisher[Any]] = publishersStack.head
  private def currentLayout: Module = moduleStack.head

  // Enters a copied module and establishes a scope that prevents internals to leak out and interfere with copies
  // of the same module.
  // We don't store the enclosing CopiedModule itself as state since we don't use it anywhere else than exit and enter
  private def enterScope(enclosing: CopiedModule): Unit = {
    subscribersStack ::= mutable.Map.empty.withDefaultValue(null)
    publishersStack ::= mutable.Map.empty.withDefaultValue(null)
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
      case (original, exposed) ⇒ assignPort(exposed, scopeSubscribers(original))
    }

    enclosing.copyOf.shape.outlets.iterator.zip(enclosing.shape.outlets.iterator).foreach {
      case (original, exposed) ⇒ assignPort(exposed, scopePublishers(original))
    }
  }

  final def materialize(): Any = {
    require(topLevel ne EmptyModule, "An empty module cannot be materialized (EmptyModule was given)")
    require(
      topLevel.isRunnable,
      s"The top level module cannot be materialized because it has unconnected ports: ${(topLevel.inPorts ++ topLevel.outPorts).mkString(", ")}")
    try materializeModule(topLevel, topLevel.attributes)
    catch {
      case NonFatal(cause) ⇒
        // PANIC!!! THE END OF THE MATERIALIZATION IS NEAR!
        // Cancels all intermediate Publishers and fails all intermediate Subscribers.
        // (This is an attempt to clean up after an exception during materialization)
        val errorPublisher = new ErrorPublisher(new MaterializationPanic(cause), "")
        for (subMap ← subscribersStack; sub ← subMap.valuesIterator)
          errorPublisher.subscribe(sub)

        for (pubMap ← publishersStack; pub ← pubMap.valuesIterator)
          pub.subscribe(new CancellingSubscriber)

        throw cause
    }
  }

  protected def mergeAttributes(parent: Attributes, current: Attributes): Attributes =
    parent and current

  protected def materializeModule(module: Module, effectiveAttributes: Attributes): Any = {
    val materializedValues = collection.mutable.HashMap.empty[Module, Any]
    var materializedValuePublishers: List[MaterializedValuePublisher] = Nil

    for (submodule ← module.subModules) {
      val subEffectiveAttributes = mergeAttributes(effectiveAttributes, submodule.attributes)
      submodule match {
        case mv: MaterializedValueSource[_] ⇒
          val pub = new MaterializedValuePublisher
          materializedValuePublishers ::= pub
          materializedValues.put(mv, ())
          assignPort(mv.shape.outlet, pub)
        case atomic if atomic.isAtomic ⇒
          materializedValues.put(atomic, materializeAtomic(atomic, subEffectiveAttributes))
        case copied: CopiedModule ⇒
          enterScope(copied)
          materializedValues.put(copied, materializeModule(copied, subEffectiveAttributes))
          exitScope(copied)
        case composite ⇒
          materializedValues.put(composite, materializeComposite(composite, subEffectiveAttributes))
      }
    }

    val mat = resolveMaterialized(module.materializedValueComputation, materializedValues)
    materializedValuePublishers foreach { pub ⇒ pub.setValue(mat) }
    mat
  }

  protected def materializeComposite(composite: Module, effectiveAttributes: Attributes): Any = {
    materializeModule(composite, effectiveAttributes)
  }

  protected def materializeAtomic(atomic: Module, effectiveAttributes: Attributes): Any

  private def resolveMaterialized(matNode: MaterializedValueNode, materializedValues: collection.Map[Module, Any]): Any = matNode match {
    case Atomic(m)          ⇒ materializedValues(m)
    case Combine(f, d1, d2) ⇒ f(resolveMaterialized(d1, materializedValues), resolveMaterialized(d2, materializedValues))
    case Transform(f, d)    ⇒ f(resolveMaterialized(d, materializedValues))
    case Ignore             ⇒ ()
  }

  final protected def assignPort(in: InPort, subscriber: Subscriber[Any]): Unit = {
    subscribers(in) = subscriber
    // Interface (unconnected) ports of the current scope will be wired when exiting the scope
    if (!currentLayout.inPorts(in)) {
      val publisher = publishers(currentLayout.upstreams(in))
      if (publisher ne null) publisher.subscribe(subscriber)
    }
  }

  final protected def assignPort(out: OutPort, publisher: Publisher[Any]): Unit = {
    publishers(out) = publisher
    // Interface (unconnected) ports of the current scope will be wired when exiting the scope
    if (!currentLayout.outPorts(out)) {
      val subscriber = subscribers(currentLayout.downstreams(out))
      if (subscriber ne null) publisher.subscribe(subscriber)
    }
  }

}
