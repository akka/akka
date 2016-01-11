/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.concurrent.TimeoutException
import akka.actor._
import akka.event.Logging
import akka.stream._
import akka.stream.impl.ReactiveStreamsCompliance._
import akka.stream.impl.StreamLayout.{ CopiedModule, Module }
import akka.stream.impl.fusing.GraphInterpreter.{ DownstreamBoundaryStageLogic, UpstreamBoundaryStageLogic, GraphAssembly }
import akka.stream.impl.{ ActorPublisher, ReactiveStreamsCompliance }
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import org.reactivestreams.{ Subscriber, Subscription }
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.control.NonFatal
import akka.event.LoggingAdapter
import akka.stream.impl.ActorMaterializerImpl
import akka.stream.impl.SubFusingActorMaterializerImpl
import scala.annotation.tailrec
import akka.stream.impl.StreamSupervisor

/**
 * INTERNAL API
 */
private[stream] case class GraphModule(assembly: GraphAssembly, shape: Shape, attributes: Attributes,
                                       matValIDs: Array[Module]) extends Module {
  override def subModules: Set[Module] = Set.empty
  override def withAttributes(newAttr: Attributes): Module = copy(attributes = newAttr)

  override final def carbonCopy: Module = {
    val newShape = shape.deepCopy()
    replaceShape(newShape)
  }

  override final def replaceShape(newShape: Shape): Module =
    CopiedModule(newShape, attributes, copyOf = this)

  override def toString: String = s"GraphModule\n  ${assembly.toString.replace("\n", "\n  ")}\n  shape=$shape, attributes=$attributes"
}

/**
 * INTERNAL API
 */
private[stream] object ActorGraphInterpreter {
  trait BoundaryEvent extends DeadLetterSuppression with NoSerializationVerificationNeeded {
    def shell: GraphInterpreterShell
  }

  final case class OnError(shell: GraphInterpreterShell, id: Int, cause: Throwable) extends BoundaryEvent
  final case class OnComplete(shell: GraphInterpreterShell, id: Int) extends BoundaryEvent
  final case class OnNext(shell: GraphInterpreterShell, id: Int, e: Any) extends BoundaryEvent
  final case class OnSubscribe(shell: GraphInterpreterShell, id: Int, subscription: Subscription) extends BoundaryEvent

  final case class RequestMore(shell: GraphInterpreterShell, id: Int, demand: Long) extends BoundaryEvent
  final case class Cancel(shell: GraphInterpreterShell, id: Int) extends BoundaryEvent
  final case class SubscribePending(shell: GraphInterpreterShell, id: Int) extends BoundaryEvent
  final case class ExposedPublisher(shell: GraphInterpreterShell, id: Int, publisher: ActorPublisher[Any]) extends BoundaryEvent

  final case class AsyncInput(shell: GraphInterpreterShell, logic: GraphStageLogic, evt: Any, handler: (Any) ⇒ Unit) extends BoundaryEvent

  case class Resume(shell: GraphInterpreterShell) extends BoundaryEvent
  case class Abort(shell: GraphInterpreterShell) extends BoundaryEvent

  final class BoundaryPublisher(parent: ActorRef, shell: GraphInterpreterShell, id: Int) extends ActorPublisher[Any](parent) {
    override val wakeUpMsg = ActorGraphInterpreter.SubscribePending(shell, id)
  }

  final class BoundarySubscription(parent: ActorRef, shell: GraphInterpreterShell, id: Int) extends Subscription {
    override def request(elements: Long): Unit = parent ! RequestMore(shell, id, elements)
    override def cancel(): Unit = parent ! Cancel(shell, id)
    override def toString = s"BoundarySubscription[$parent, $id]"
  }

  final class BoundarySubscriber(parent: ActorRef, shell: GraphInterpreterShell, id: Int) extends Subscriber[Any] {
    override def onError(cause: Throwable): Unit = {
      ReactiveStreamsCompliance.requireNonNullException(cause)
      parent ! OnError(shell, id, cause)
    }
    override def onComplete(): Unit = parent ! OnComplete(shell, id)
    override def onNext(element: Any): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(element)
      parent ! OnNext(shell, id, element)
    }
    override def onSubscribe(subscription: Subscription): Unit = {
      ReactiveStreamsCompliance.requireNonNullSubscription(subscription)
      parent ! OnSubscribe(shell, id, subscription)
    }
  }

  def props(shell: GraphInterpreterShell): Props =
    Props(new ActorGraphInterpreter(shell)).withDeploy(Deploy.local)

  class BatchingActorInputBoundary(size: Int, id: Int) extends UpstreamBoundaryStageLogic[Any] {
    require(size > 0, "buffer size cannot be zero")
    require((size & (size - 1)) == 0, "buffer size must be a power of two")

    private var upstream: Subscription = _
    private val inputBuffer = Array.ofDim[AnyRef](size)
    private var inputBufferElements = 0
    private var nextInputElementCursor = 0
    private var upstreamCompleted = false
    private var downstreamCanceled = false
    private val IndexMask = size - 1

    private def requestBatchSize = math.max(1, inputBuffer.length / 2)
    private var batchRemaining = requestBatchSize

    val out: Outlet[Any] = Outlet[Any]("UpstreamBoundary" + id)
    out.id = 0

    private def dequeue(): Any = {
      val elem = inputBuffer(nextInputElementCursor)
      require(elem ne null, "Internal queue must never contain a null")
      inputBuffer(nextInputElementCursor) = null

      batchRemaining -= 1
      if (batchRemaining == 0 && !upstreamCompleted) {
        tryRequest(upstream, requestBatchSize)
        batchRemaining = requestBatchSize
      }

      inputBufferElements -= 1
      nextInputElementCursor = (nextInputElementCursor + 1) & IndexMask
      elem
    }
    private def clear(): Unit = {
      java.util.Arrays.fill(inputBuffer, 0, inputBuffer.length, null)
      inputBufferElements = 0
    }

    def cancel(): Unit = {
      downstreamCanceled = true
      if (!upstreamCompleted) {
        upstreamCompleted = true
        if (upstream ne null) tryCancel(upstream)
        clear()
      }
    }

    def onNext(elem: Any): Unit = {
      if (!upstreamCompleted) {
        if (inputBufferElements == size) throw new IllegalStateException("Input buffer overrun")
        inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem.asInstanceOf[AnyRef]
        inputBufferElements += 1
        if (isAvailable(out)) push(out, dequeue())
      }
    }

    def onError(e: Throwable): Unit =
      if (!upstreamCompleted || !downstreamCanceled) {
        upstreamCompleted = true
        clear()
        fail(out, e)
      }

    // Call this when an error happens that does not come from the usual onError channel
    // (exceptions while calling RS interfaces, abrupt termination etc)
    def onInternalError(e: Throwable): Unit = {
      if (!(upstreamCompleted || downstreamCanceled) && (upstream ne null)) {
        upstream.cancel()
      }
      if (!isClosed(out)) onError(e)
    }

    def onComplete(): Unit =
      if (!upstreamCompleted) {
        upstreamCompleted = true
        if (inputBufferElements == 0) complete(out)
      }

    def onSubscribe(subscription: Subscription): Unit = {
      require(subscription != null, "Subscription cannot be null")
      if (upstreamCompleted)
        tryCancel(subscription)
      else if (downstreamCanceled) {
        upstreamCompleted = true
        tryCancel(subscription)
      } else {
        upstream = subscription
        // Prefetch
        tryRequest(upstream, inputBuffer.length)
      }
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (inputBufferElements > 1) push(out, dequeue())
        else if (inputBufferElements == 1) {
          if (upstreamCompleted) {
            push(out, dequeue())
            complete(out)
          } else push(out, dequeue())
        } else if (upstreamCompleted) {
          complete(out)
        }
      }

      override def onDownstreamFinish(): Unit = cancel()

      override def toString: String = BatchingActorInputBoundary.this.toString
    })

    override def toString: String = s"BatchingActorInputBoundary(id=$id, fill=$inputBufferElements/$size, completed=$upstreamCompleted, canceled=$downstreamCanceled)"
  }

  private[stream] class ActorOutputBoundary(actor: ActorRef, shell: GraphInterpreterShell, id: Int) extends DownstreamBoundaryStageLogic[Any] {
    val in: Inlet[Any] = Inlet[Any]("UpstreamBoundary" + id)
    in.id = 0

    private var exposedPublisher: ActorPublisher[Any] = _

    private var subscriber: Subscriber[Any] = _
    private var downstreamDemand: Long = 0L
    // This flag is only used if complete/fail is called externally since this op turns into a Finished one inside the
    // interpreter (i.e. inside this op this flag has no effects since if it is completed the op will not be invoked)
    private var downstreamCompleted = false
    // when upstream failed before we got the exposed publisher
    private var upstreamFailed: Option[Throwable] = None
    private var upstreamCompleted: Boolean = false

    private def onNext(elem: Any): Unit = {
      downstreamDemand -= 1
      tryOnNext(subscriber, elem)
    }

    private def complete(): Unit = {
      // No need to complete if had already been cancelled, or we closed earlier
      if (!(upstreamCompleted || downstreamCompleted)) {
        upstreamCompleted = true
        if (exposedPublisher ne null) exposedPublisher.shutdown(None)
        if (subscriber ne null) tryOnComplete(subscriber)
      }
    }

    def fail(e: Throwable): Unit = {
      // No need to fail if had already been cancelled, or we closed earlier
      if (!(downstreamCompleted || upstreamCompleted)) {
        upstreamCompleted = true
        upstreamFailed = Some(e)
        if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
        if ((subscriber ne null) && !e.isInstanceOf[SpecViolation]) tryOnError(subscriber, e)
      }
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        onNext(grab(in))
        if (downstreamCompleted) cancel(in)
        else if (downstreamDemand > 0) pull(in)
      }

      override def onUpstreamFinish(): Unit = complete()

      override def onUpstreamFailure(cause: Throwable): Unit = fail(cause)

      override def toString: String = ActorOutputBoundary.this.toString
    })

    def subscribePending(): Unit =
      exposedPublisher.takePendingSubscribers() foreach { sub ⇒
        if (subscriber eq null) {
          subscriber = sub
          tryOnSubscribe(subscriber, new BoundarySubscription(actor, shell, id))
          if (GraphInterpreter.Debug) println(s"${interpreter.Name}  subscribe subscriber=$sub")
        } else
          rejectAdditionalSubscriber(subscriber, s"${Logging.simpleName(this)}")
      }

    def exposedPublisher(publisher: ActorPublisher[Any]): Unit = {
      upstreamFailed match {
        case _: Some[_] ⇒
          publisher.shutdown(upstreamFailed)
        case _ ⇒
          if (upstreamCompleted) publisher.shutdown(None)
          else exposedPublisher = publisher
      }
    }

    def requestMore(elements: Long): Unit = {
      if (elements < 1) {
        cancel(in)
        fail(ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException)
      } else {
        downstreamDemand += elements
        if (downstreamDemand < 0)
          downstreamDemand = Long.MaxValue // Long overflow, Reactive Streams Spec 3:17: effectively unbounded
        if (!hasBeenPulled(in) && !isClosed(in)) pull(in)
      }
    }

    def cancel(): Unit = {
      downstreamCompleted = true
      subscriber = null
      exposedPublisher.shutdown(Some(new ActorPublisher.NormalShutdownException))
      cancel(in)
    }

    override def toString: String = s"ActorOutputBoundary(id=$id, demand=$downstreamDemand, finished=$downstreamCompleted)"
  }

}

/**
 * INTERNAL API
 */
private[stream] final class GraphInterpreterShell(
  assembly: GraphAssembly,
  inHandlers: Array[InHandler],
  outHandlers: Array[OutHandler],
  logics: Array[GraphStageLogic],
  shape: Shape,
  settings: ActorMaterializerSettings,
  val mat: ActorMaterializerImpl) {

  import ActorGraphInterpreter._

  private var self: ActorRef = _
  lazy val log = Logging(mat.system.eventStream, self)

  lazy val interpreter = new GraphInterpreter(assembly, mat, log, inHandlers, outHandlers, logics,
    (logic, event, handler) ⇒ self ! AsyncInput(this, logic, event, handler), settings.fuzzingMode)

  private val inputs = new Array[BatchingActorInputBoundary](shape.inlets.size)
  private val outputs = new Array[ActorOutputBoundary](shape.outlets.size)

  private var subscribesPending = inputs.length
  private var publishersPending = outputs.length

  def dumpWaits(): Unit = interpreter.dumpWaits()

  /*
   * Limits the number of events processed by the interpreter before scheduling
   * a self-message for fairness with other actors. The basic assumption here is
   * to give each input buffer slot a chance to run through the whole pipeline
   * and back (for the demand).
   */
  private val eventLimit = settings.maxInputBufferSize * (assembly.ins.length + assembly.outs.length)

  // Limits the number of events processed by the interpreter on an abort event.
  // TODO: Better heuristic here
  private val abortLimit = eventLimit * 2
  private var resumeScheduled = false

  def isInitialized: Boolean = self != null

  def init(self: ActorRef, subMat: SubFusingActorMaterializerImpl): Unit = {
    this.self = self
    var i = 0
    while (i < inputs.length) {
      val in = new BatchingActorInputBoundary(settings.maxInputBufferSize, i)
      inputs(i) = in
      interpreter.attachUpstreamBoundary(i, in)
      i += 1
    }
    val offset = assembly.connectionCount - outputs.length
    i = 0
    while (i < outputs.length) {
      val out = new ActorOutputBoundary(self, this, i)
      outputs(i) = out
      interpreter.attachDownstreamBoundary(i + offset, out)
      i += 1
    }
    interpreter.init(subMat)
    runBatch()
  }

  def receive(event: BoundaryEvent): Unit =
    if (waitingForShutdown) event match {
      case ExposedPublisher(_, id, publisher) ⇒
        outputs(id).exposedPublisher(publisher)
        publishersPending -= 1
        if (canShutDown) _isTerminated = true
      case OnSubscribe(_, _, sub) ⇒
        tryCancel(sub)
        subscribesPending -= 1
        if (canShutDown) _isTerminated = true
      case Abort(_) ⇒
        tryAbort(new TimeoutException("Streaming actor has been already stopped processing (normally), but not all of its " +
          s"inputs or outputs have been subscribed in [${settings.subscriptionTimeoutSettings.timeout}}]. Aborting actor now."))
      case _ ⇒ // Ignore, there is nothing to do anyway
    }
    else event match {
      // Cases that are most likely on the hot path, in decreasing order of frequency
      case OnNext(_, id: Int, e: Any) ⇒
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onNext $e id=$id")
        inputs(id).onNext(e)
        runBatch()
      case RequestMore(_, id: Int, demand: Long) ⇒
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  request  $demand id=$id")
        outputs(id).requestMore(demand)
        runBatch()
      case Resume(_) ⇒
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  resume")
        resumeScheduled = false
        if (interpreter.isSuspended) runBatch()
      case AsyncInput(_, logic, event, handler) ⇒
        interpreter.runAsyncInput(logic, event, handler)
        runBatch()

      // Initialization and completion messages
      case OnError(_, id: Int, cause: Throwable) ⇒
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onError id=$id")
        inputs(id).onError(cause)
        runBatch()
      case OnComplete(_, id: Int) ⇒
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onComplete id=$id")
        inputs(id).onComplete()
        runBatch()
      case OnSubscribe(_, id: Int, subscription: Subscription) ⇒
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onSubscribe id=$id")
        subscribesPending -= 1
        inputs(id).onSubscribe(subscription)
        runBatch()
      case Cancel(_, id: Int) ⇒
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  cancel id=$id")
        outputs(id).cancel()
        runBatch()
      case SubscribePending(_, id: Int) ⇒
        outputs(id).subscribePending()
      case ExposedPublisher(_, id, publisher) ⇒
        publishersPending -= 1
        outputs(id).exposedPublisher(publisher)
    }

  private var _isTerminated = false
  def isTerminated: Boolean = _isTerminated

  private def canShutDown: Boolean = subscribesPending + publishersPending == 0

  private var waitingForShutdown: Boolean = false

  private val resume = Resume(this)

  private def runBatch(): Unit = {
    try {
      val effectiveLimit = {
        if (!settings.fuzzingMode) eventLimit
        else {
          if (ThreadLocalRandom.current().nextBoolean()) Thread.`yield`()
          ThreadLocalRandom.current().nextInt(2) // 1 or zero events to be processed
        }
      }
      interpreter.execute(effectiveLimit)
      if (interpreter.isCompleted) {
        // Cannot stop right away if not completely subscribed
        if (canShutDown) _isTerminated = true
        else {
          waitingForShutdown = true
          mat.scheduleOnce(settings.subscriptionTimeoutSettings.timeout, new Runnable {
            override def run(): Unit = self ! Abort(GraphInterpreterShell.this)
          })
        }
      } else if (interpreter.isSuspended && !resumeScheduled) {
        resumeScheduled = true
        self ! resume
      }
    } catch {
      case NonFatal(e) ⇒
        tryAbort(e)
    }
  }

  /**
   * Attempts to abort execution, by first propagating the reason given until either
   *  - the interpreter successfully finishes
   *  - the event limit is reached
   *  - a new error is encountered
   */
  def tryAbort(ex: Throwable): Unit = {
    // This should handle termination while interpreter is running. If the upstream have been closed already this
    // call has no effect and therefore do the right thing: nothing.
    try {
      inputs.foreach(_.onInternalError(ex))
      interpreter.execute(abortLimit)
      interpreter.finish()
    } catch {
      case NonFatal(_) ⇒
    } finally {
      _isTerminated = true
      // Will only have an effect if the above call to the interpreter failed to emit a proper failure to the downstream
      // otherwise this will have no effect
      outputs.foreach(_.fail(ex))
      inputs.foreach(_.cancel())
    }
  }

  override def toString: String = s"GraphInterpreterShell\n  ${assembly.toString.replace("\n", "\n  ")}"
}

/**
 * INTERNAL API
 */
private[stream] class ActorGraphInterpreter(_initial: GraphInterpreterShell) extends Actor with ActorLogging {
  import ActorGraphInterpreter._

  var activeInterpreters = Set.empty[GraphInterpreterShell]
  var newShells: List[GraphInterpreterShell] = Nil
  val subFusingMaterializerImpl = new SubFusingActorMaterializerImpl(_initial.mat, registerShell)

  def tryInit(shell: GraphInterpreterShell): Boolean =
    try {
      shell.init(self, subFusingMaterializerImpl)
      if (GraphInterpreter.Debug) println(s"registering new shell in ${_initial}\n  ${shell.toString.replace("\n", "\n  ")}")
      if (shell.isTerminated) false
      else {
        activeInterpreters += shell
        true
      }
    } catch {
      case NonFatal(e) ⇒
        log.error(e, "initialization of GraphInterpreterShell failed for {}", shell)
        false
    }

  def registerShell(shell: GraphInterpreterShell): ActorRef = {
    newShells ::= shell
    self ! Resume
    self
  }

  /*
   * Avoid performing the initialization (which start the first runBatch())
   * within registerShell in order to avoid unbounded recursion.
   */
  @tailrec private def finishShellRegistration(): Unit =
    newShells match {
      case Nil ⇒ if (activeInterpreters.isEmpty) context.stop(self)
      case shell :: tail ⇒
        newShells = tail
        if (shell.isInitialized) {
          // yes, this steals another shell’s Resume, but that’s okay because extra ones will just not do anything
          finishShellRegistration()
        } else tryInit(shell)
    }

  override def preStart(): Unit = {
    tryInit(_initial)
    if (activeInterpreters.isEmpty) context.stop(self)
  }

  override def receive: Receive = {
    case b: BoundaryEvent ⇒
      val shell = b.shell
      if (!shell.isTerminated && (shell.isInitialized || tryInit(shell))) {
        shell.receive(b)
        if (shell.isTerminated) {
          activeInterpreters -= shell
          if (activeInterpreters.isEmpty && newShells.isEmpty) context.stop(self)
        }
      }
    case Resume ⇒ finishShellRegistration()
    case StreamSupervisor.PrintDebugDump ⇒
      println(s"activeShells:")
      activeInterpreters.foreach { shell ⇒
        println("  " + shell.toString.replace("\n", "\n  "))
        shell.interpreter.dumpWaits()
      }
      println(s"newShells:")
      newShells.foreach { shell ⇒
        println("  " + shell.toString.replace("\n", "\n  "))
        shell.interpreter.dumpWaits()
      }
  }

  override def postStop(): Unit = activeInterpreters.foreach(_.tryAbort(AbruptTerminationException(self)))
}
