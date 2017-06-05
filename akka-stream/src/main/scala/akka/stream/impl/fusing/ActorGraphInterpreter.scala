/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import java.util
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import akka.actor._
import akka.annotation.InternalApi
import akka.event.Logging
import akka.stream._
import akka.stream.impl.ReactiveStreamsCompliance._
import akka.stream.impl.fusing.GraphInterpreter.{ Connection, DownstreamBoundaryStageLogic, UpstreamBoundaryStageLogic }
import akka.stream.impl.{ SubFusingActorMaterializerImpl, _ }
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import akka.util.OptionVal
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ActorGraphInterpreter {

  object Resume extends DeadLetterSuppression with NoSerializationVerificationNeeded

  trait BoundaryEvent extends DeadLetterSuppression with NoSerializationVerificationNeeded {
    def shell: GraphInterpreterShell
    def execute(eventLimit: Int): Int
  }

  trait SimpleBoundaryEvent extends BoundaryEvent {
    final override def execute(eventLimit: Int): Int = {
      val wasNotShutdown = !shell.interpreter.isStageCompleted(logic)
      execute()
      if (wasNotShutdown) shell.interpreter.afterStageHasRun(logic)
      shell.runBatch(eventLimit)
    }

    def logic: GraphStageLogic

    def execute(): Unit
  }

  def props(shell: GraphInterpreterShell): Props =
    Props(new ActorGraphInterpreter(shell)).withDeploy(Deploy.local)

  class BatchingActorInputBoundary(
    size:             Int,
    shell:            GraphInterpreterShell,
    publisher:        Publisher[Any],
    internalPortName: String) extends UpstreamBoundaryStageLogic[Any] with OutHandler {

    final case class OnError(shell: GraphInterpreterShell, cause: Throwable) extends SimpleBoundaryEvent {
      override def execute(): Unit = {
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onError port=$internalPortName")
        BatchingActorInputBoundary.this.onError(cause)
      }

      override def logic: GraphStageLogic = BatchingActorInputBoundary.this
    }
    final case class OnComplete(shell: GraphInterpreterShell) extends SimpleBoundaryEvent {
      override def execute(): Unit = {
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onComplete port=$internalPortName")
        BatchingActorInputBoundary.this.onComplete()
      }

      override def logic: GraphStageLogic = BatchingActorInputBoundary.this
    }
    final case class OnNext(shell: GraphInterpreterShell, e: Any) extends SimpleBoundaryEvent {
      override def execute(): Unit = {
        if (GraphInterpreter.Debug) println(s"${interpreter.Name} onNext $e port=$internalPortName")
        BatchingActorInputBoundary.this.onNext(e)
      }

      override def logic: GraphStageLogic = BatchingActorInputBoundary.this
    }
    final case class OnSubscribe(shell: GraphInterpreterShell, subscription: Subscription) extends SimpleBoundaryEvent {
      override def execute(): Unit = {
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onSubscribe port=$internalPortName")
        shell.subscribeArrived()
        BatchingActorInputBoundary.this.onSubscribe(subscription)
      }

      override def logic: GraphStageLogic = BatchingActorInputBoundary.this
    }

    if (size <= 0) throw new IllegalArgumentException("buffer size cannot be zero")
    if ((size & (size - 1)) != 0) throw new IllegalArgumentException("buffer size must be a power of two")

    private var actor: ActorRef = ActorRef.noSender
    private var upstream: Subscription = _
    private val inputBuffer = new Array[AnyRef](size)
    private var inputBufferElements = 0
    private var nextInputElementCursor = 0
    private var upstreamCompleted = false
    private var downstreamCanceled = false
    private val IndexMask = size - 1

    private def requestBatchSize = math.max(1, inputBuffer.length / 2)
    private var batchRemaining = requestBatchSize

    val out: Outlet[Any] = Outlet[Any]("UpstreamBoundary:" + internalPortName)
    out.id = 0

    def setActor(actor: ActorRef): Unit = this.actor = actor

    override def preStart(): Unit = {
      publisher.subscribe(
        new Subscriber[Any] {
          override def onError(t: Throwable): Unit = {
            ReactiveStreamsCompliance.requireNonNullException(t)
            actor ! OnError(shell, t)
          }

          override def onSubscribe(s: Subscription): Unit = {
            ReactiveStreamsCompliance.requireNonNullSubscription(s)
            actor ! OnSubscribe(shell, s)
          }

          override def onComplete(): Unit = {
            actor ! OnComplete(shell)
          }

          override def onNext(t: Any): Unit = {
            ReactiveStreamsCompliance.requireNonNullElement(t)
            actor ! OnNext(shell, t)
          }
        })
    }

    private def dequeue(): Any = {
      val elem = inputBuffer(nextInputElementCursor)
      if (elem eq null) throw new IllegalArgumentException("Internal queue must never contain a null")
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
      ReactiveStreamsCompliance.requireNonNullSubscription(subscription)
      if (upstreamCompleted) {
        tryCancel(subscription)
      } else if (downstreamCanceled) {
        upstreamCompleted = true
        tryCancel(subscription)
      } else {
        upstream = subscription
        // Prefetch
        tryRequest(upstream, inputBuffer.length)
      }
    }

    setHandler(out, this)

    override def onPull(): Unit = {
      try {
        if (inputBufferElements > 1) push(out, dequeue())
        else if (inputBufferElements == 1) {
          if (upstreamCompleted) {
            push(out, dequeue())
            complete(out)
          } else push(out, dequeue())
        } else if (upstreamCompleted) {
          complete(out)
        }
      } catch {
        case s: SpecViolation ⇒ shell.tryAbort(s)
      }
    }

    override def onDownstreamFinish(): Unit =
      try cancel()
      catch {
        case s: SpecViolation ⇒ shell.tryAbort(s)
      }

    override def toString: String = s"BatchingActorInputBoundary(forPort=$internalPortName, fill=$inputBufferElements/$size, completed=$upstreamCompleted, canceled=$downstreamCanceled)"
  }

  final case class SubscribePending(boundary: ActorOutputBoundary) extends SimpleBoundaryEvent {
    override def execute(): Unit = boundary.subscribePending()

    override def shell: GraphInterpreterShell = boundary.shell

    override def logic: GraphStageLogic = boundary
  }

  final case class RequestMore(boundary: ActorOutputBoundary, demand: Long) extends SimpleBoundaryEvent {
    override def execute(): Unit = {
      if (GraphInterpreter.Debug) println(s"${boundary.shell.interpreter.Name}  request  $demand port=${boundary.internalPortName}")
      boundary.requestMore(demand)
    }
    override def shell: GraphInterpreterShell = boundary.shell
    override def logic: GraphStageLogic = boundary
  }
  final case class Cancel(boundary: ActorOutputBoundary) extends SimpleBoundaryEvent {
    override def execute(): Unit = {
      if (GraphInterpreter.Debug) println(s"${boundary.shell.interpreter.Name}  cancel port=${boundary.internalPortName}")
      boundary.cancel()
    }

    override def shell: GraphInterpreterShell = boundary.shell
    override def logic: GraphStageLogic = boundary
  }

  private[stream] class OutputBoundaryPublisher(boundary: ActorOutputBoundary, internalPortName: String) extends Publisher[Any] {
    import ReactiveStreamsCompliance._

    // The subscriber of an subscription attempt is first placed in this list of pending subscribers.
    // The actor will call takePendingSubscribers to remove it from the list when it has received the
    // SubscribePending message. The AtomicReference is set to null by the shutdown method, which is
    // called by the actor from postStop. Pending (unregistered) subscription attempts are denied by
    // the shutdown method. Subscription attempts after shutdown can be denied immediately.
    private val pendingSubscribers = new AtomicReference[immutable.Seq[Subscriber[Any]]](Nil)

    protected val wakeUpMsg: Any = SubscribePending(boundary)

    override def subscribe(subscriber: Subscriber[_ >: Any]): Unit = {
      requireNonNullSubscriber(subscriber)
      @tailrec def doSubscribe(): Unit = {
        val current = pendingSubscribers.get
        if (current eq null)
          reportSubscribeFailure(subscriber)
        else {
          if (pendingSubscribers.compareAndSet(current, subscriber +: current)) {
            if (boundary.getActor ne null) boundary.getActor ! wakeUpMsg
          } else {
            doSubscribe() // CAS retry
          }
        }
      }

      doSubscribe()
    }

    def takePendingSubscribers(): immutable.Seq[Subscriber[Any]] = {
      val pending = pendingSubscribers.getAndSet(Nil)
      if (pending eq null) Nil else pending.reverse
    }

    def shutdown(reason: Option[Throwable]): Unit = {
      shutdownReason = OptionVal(reason.orNull)
      pendingSubscribers.getAndSet(null) match {
        case null    ⇒ // already called earlier
        case pending ⇒ pending foreach reportSubscribeFailure
      }
    }

    @volatile private var shutdownReason: OptionVal[Throwable] = OptionVal.None

    private def reportSubscribeFailure(subscriber: Subscriber[Any]): Unit =
      try shutdownReason match {
        case OptionVal.Some(e: SpecViolation) ⇒ // ok, not allowed to call onError
        case OptionVal.Some(e) ⇒
          tryOnSubscribe(subscriber, CancelledSubscription)
          tryOnError(subscriber, e)
        case OptionVal.None ⇒
          tryOnSubscribe(subscriber, CancelledSubscription)
          tryOnComplete(subscriber)
      } catch {
        case _: SpecViolation ⇒ // nothing to do
      }

    override def toString: String = s"Publisher[$internalPortName]"
  }

  private[stream] class ActorOutputBoundary(val shell: GraphInterpreterShell, val internalPortName: String)
    extends DownstreamBoundaryStageLogic[Any] with InHandler {

    val in: Inlet[Any] = Inlet[Any]("UpstreamBoundary:" + internalPortName)
    in.id = 0

    val publisher = new OutputBoundaryPublisher(this, internalPortName)

    @volatile private var actor: ActorRef = null
    def setActor(actor: ActorRef): Unit = this.actor = actor
    def getActor: ActorRef = this.actor

    private var subscriber: Subscriber[Any] = _
    private var downstreamDemand: Long = 0L
    // This flag is only used if complete/fail is called externally since this op turns into a Finished one inside the
    // interpreter (i.e. inside this op this flag has no effects since if it is completed the op will not be invoked)
    private var downstreamCompleted = false
    // when upstream failed before we got the exposed publisher
    private var upstreamFailed: OptionVal[Throwable] = OptionVal.None
    private var upstreamCompleted: Boolean = false

    private def onNext(elem: Any): Unit = {
      downstreamDemand -= 1
      tryOnNext(subscriber, elem)
    }

    private def complete(): Unit = {
      // No need to complete if had already been cancelled, or we closed earlier
      if (!(upstreamCompleted || downstreamCompleted)) {
        upstreamCompleted = true
        publisher.shutdown(None)
        if (subscriber ne null) tryOnComplete(subscriber)
      }
    }

    def fail(e: Throwable): Unit = {
      // No need to fail if had already been cancelled, or we closed earlier
      if (!(downstreamCompleted || upstreamCompleted)) {
        upstreamCompleted = true
        upstreamFailed = OptionVal.Some(e)
        publisher.shutdown(Some(e))
        if ((subscriber ne null) && !e.isInstanceOf[SpecViolation]) tryOnError(subscriber, e)
      }
    }

    setHandler(in, this)

    override def onPush(): Unit = {
      try {
        onNext(grab(in))
        if (downstreamCompleted) cancel(in)
        else if (downstreamDemand > 0) pull(in)
      } catch {
        case s: SpecViolation ⇒ shell.tryAbort(s)
      }
    }

    override def onUpstreamFinish(): Unit =
      try complete()
      catch {
        case s: SpecViolation ⇒ shell.tryAbort(s)
      }

    override def onUpstreamFailure(cause: Throwable): Unit =
      try fail(cause)
      catch {
        case s: SpecViolation ⇒ shell.tryAbort(s)
      }

    def subscribePending(): Unit =
      publisher.takePendingSubscribers() foreach { sub ⇒
        if (subscriber eq null) {
          subscriber = sub
          val subscription = new Subscription {
            override def request(elements: Long): Unit = actor ! RequestMore(ActorOutputBoundary.this, elements)
            override def cancel(): Unit = actor ! Cancel(ActorOutputBoundary.this)
            override def toString = s"BoundarySubscription[$actor, $internalPortName]"
          }

          tryOnSubscribe(subscriber, subscription)
          // TODO: add interpreter name back
          if (GraphInterpreter.Debug) println(s" subscribe subscriber=$sub")
        } else
          rejectAdditionalSubscriber(subscriber, s"${Logging.simpleName(this)}")
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
      publisher.shutdown(Some(new ActorPublisher.NormalShutdownException))
      cancel(in)
    }

    override def toString: String = s"ActorOutputBoundary(port=$internalPortName, demand=$downstreamDemand, finished=$downstreamCompleted)"
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class GraphInterpreterShell(
  var connections: Array[Connection],
  var logics:      Array[GraphStageLogic],
  settings:        ActorMaterializerSettings,
  val mat:         ExtendedActorMaterializer) {

  import ActorGraphInterpreter._

  private var self: ActorRef = _
  lazy val log = Logging(mat.system.eventStream, self)

  final case class AsyncInput(shell: GraphInterpreterShell, logic: GraphStageLogic, evt: Any, handler: (Any) ⇒ Unit) extends BoundaryEvent {
    override def execute(eventLimit: Int): Int = {
      if (!waitingForShutdown) {
        interpreter.runAsyncInput(logic, evt, handler)
        if (eventLimit == 1 && interpreter.isSuspended) {
          sendResume(true)
          0
        } else runBatch(eventLimit - 1)
      } else eventLimit
    }
  }

  final case class ResumeShell(shell: GraphInterpreterShell) extends BoundaryEvent {
    override def execute(eventLimit: Int): Int =
      if (!waitingForShutdown) {
        if (GraphInterpreter.Debug) println(s"${interpreter.Name}  resume")
        if (interpreter.isSuspended) runBatch(eventLimit) else eventLimit
      } else eventLimit
  }

  final case class Abort(shell: GraphInterpreterShell) extends BoundaryEvent {
    override def execute(eventLimit: Int): Int = {
      if (waitingForShutdown) {
        subscribesPending = 0
        tryAbort(new TimeoutException("Streaming actor has been already stopped processing (normally), but not all of its " +
          s"inputs or outputs have been subscribed in [${settings.subscriptionTimeoutSettings.timeout}}]. Aborting actor now."))
      }
      0
    }
  }

  private var enqueueToShortCircuit: (Any) ⇒ Unit = _

  lazy val interpreter: GraphInterpreter = new GraphInterpreter(mat, log, logics, connections,
    (logic, event, handler) ⇒ {
      val asyncInput = AsyncInput(this, logic, event, handler)
      val currentInterpreter = GraphInterpreter.currentInterpreterOrNull
      if (currentInterpreter == null || (currentInterpreter.context ne self))
        self ! asyncInput
      else enqueueToShortCircuit(asyncInput)
    }, settings.fuzzingMode, self)

  // TODO: really needed?
  private var subscribesPending = 0

  private var inputs: List[BatchingActorInputBoundary] = Nil
  private var outputs: List[ActorOutputBoundary] = Nil

  def dumpWaits(): Unit = interpreter.dumpWaits()

  /*
   * Limits the number of events processed by the interpreter before scheduling
   * a self-message for fairness with other actors. The basic assumption here is
   * to give each input buffer slot a chance to run through the whole pipeline
   * and back (for the demand).
   *
   * Considered use case:
   *  - assume a composite Sink of one expand and one fold
   *  - assume an infinitely fast source of data
   *  - assume maxInputBufferSize == 1
   *  - if the event limit is greater than maxInputBufferSize * (ins + outs) than there will always be expand activity
   *  because no data can enter “fast enough” from the outside
   */
  // TODO: Fix event limit heuristic
  val shellEventLimit = settings.maxInputBufferSize * 16
  // Limits the number of events processed by the interpreter on an abort event.
  // TODO: Better heuristic here
  private val abortLimit = shellEventLimit * 2
  private var resumeScheduled = false

  def isInitialized: Boolean = self != null
  def init(self: ActorRef, subMat: SubFusingActorMaterializerImpl, enqueueToShortCircuit: (Any) ⇒ Unit, eventLimit: Int): Int = {
    this.self = self
    this.enqueueToShortCircuit = enqueueToShortCircuit
    var i = 0
    while (i < logics.length) {
      logics(i) match {
        case in: BatchingActorInputBoundary ⇒
          in.setActor(self)
          subscribesPending += 1
          inputs ::= in
        case out: ActorOutputBoundary ⇒
          out.setActor(self)
          out.subscribePending()
          outputs ::= out
        case _ ⇒
      }
      i += 1
    }

    interpreter.init(subMat)
    runBatch(eventLimit)
  }

  def processEvent(event: BoundaryEvent, eventLimit: Int): Int = {
    resumeScheduled = false
    event.execute(eventLimit)
  }

  private var interpreterCompleted = false
  def isTerminated: Boolean = interpreterCompleted && canShutDown

  private def canShutDown: Boolean = subscribesPending == 0

  def subscribeArrived(): Unit = {
    subscribesPending -= 1
  }

  private var waitingForShutdown: Boolean = false

  val resume = ResumeShell(this)

  def sendResume(sendResume: Boolean): Unit = {
    resumeScheduled = true
    if (sendResume) self ! resume
    else enqueueToShortCircuit(resume)
  }

  def runBatch(actorEventLimit: Int): Int = {
    try {
      val usingShellLimit = shellEventLimit < actorEventLimit
      val remainingQuota = interpreter.execute(Math.min(actorEventLimit, shellEventLimit))
      if (interpreter.isCompleted) {
        // Cannot stop right away if not completely subscribed
        if (canShutDown) interpreterCompleted = true
        else {
          waitingForShutdown = true
          mat.scheduleOnce(settings.subscriptionTimeoutSettings.timeout, new Runnable {
            override def run(): Unit = self ! Abort(GraphInterpreterShell.this)
          })
        }
      } else if (interpreter.isSuspended && !resumeScheduled) sendResume(!usingShellLimit)

      if (usingShellLimit) actorEventLimit - shellEventLimit + remainingQuota else remainingQuota
    } catch {
      case NonFatal(e) ⇒
        tryAbort(e)
        actorEventLimit - 1
    }
  }

  /**
   * Attempts to abort execution, by first propagating the reason given until either
   *  - the interpreter successfully finishes
   *  - the event limit is reached
   *  - a new error is encountered
   */
  def tryAbort(ex: Throwable): Unit = {
    val reason = ex match {
      case s: SpecViolation ⇒
        new IllegalStateException("Shutting down because of violation of the Reactive Streams specification.", s)
      case _ ⇒ ex
    }

    // This should handle termination while interpreter is running. If the upstream have been closed already this
    // call has no effect and therefore does the right thing: nothing.
    try {
      inputs.foreach(_.onInternalError(reason))
      interpreter.execute(abortLimit)
      interpreter.finish()
    } catch {
      case NonFatal(_) ⇒
      // We are already handling an abort caused by an error, there is nothing we can do with new errors caused
      // by the abort itself. We just give up here.
    } finally {
      interpreterCompleted = true
      // Will only have an effect if the above call to the interpreter failed to emit a proper failure to the downstream
      // otherwise this will have no effect
      outputs.foreach(_.fail(reason))
      inputs.foreach(_.cancel())
    }
  }

  override def toString: String = {
    val builder = StringBuilder.newBuilder
    builder.append("GraphInterpreterShell(\n  logics: [\n")
    interpreter.logics.foreach { logic ⇒
      builder.append("    ")
        .append(logic.originalStage.getOrElse(logic).toString)
        .append(" attrs: [")
        .append(logic.attributes.attributeList.mkString(", "))
        .append("],\n")
    }
    builder.setLength(builder.length - 2)
    builder.append("\n  ],\n  connections: [\n")
    interpreter.connections.foreach { connection ⇒
      builder
        .append("    ")
        .append(if (connection == null) "null" else connection.toString)
        .append(",\n")
    }
    builder.setLength(builder.length - 2)
    builder.append("\n  ]\n)")
    builder.toString()
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ActorGraphInterpreter(_initial: GraphInterpreterShell) extends Actor with ActorLogging {
  import ActorGraphInterpreter._

  var activeInterpreters = Set.empty[GraphInterpreterShell]
  var newShells: List[GraphInterpreterShell] = Nil
  val subFusingMaterializerImpl = new SubFusingActorMaterializerImpl(_initial.mat, registerShell)

  def tryInit(shell: GraphInterpreterShell): Boolean =
    try {
      currentLimit = shell.init(self, subFusingMaterializerImpl, enqueueToShortCircuit, currentLimit)
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

  //this limits number of messages that can be processed synchronously during one actor receive.
  private val eventLimit: Int = _initial.mat.settings.syncProcessingLimit
  private var currentLimit: Int = eventLimit
  //this is a var in order to save the allocation when no short-circuiting actually happens
  private var shortCircuitBuffer: util.ArrayDeque[Any] = null

  def enqueueToShortCircuit(input: Any): Unit = {
    if (shortCircuitBuffer eq null) shortCircuitBuffer = new util.ArrayDeque[Any]()
    shortCircuitBuffer.addLast(input)
  }

  def registerShell(shell: GraphInterpreterShell): ActorRef = {
    newShells ::= shell
    enqueueToShortCircuit(Resume)
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
        } else if (!tryInit(shell)) {
          if (activeInterpreters.isEmpty) finishShellRegistration()
        }
    }

  override def preStart(): Unit = {
    tryInit(_initial)
    if (activeInterpreters.isEmpty) context.stop(self)
    else if (shortCircuitBuffer != null) shortCircuitBatch()
  }

  private def shortCircuitBatch(): Unit = {
    while (!shortCircuitBuffer.isEmpty && currentLimit > 0 && activeInterpreters.nonEmpty)
      shortCircuitBuffer.poll() match {
        case b: BoundaryEvent ⇒ processEvent(b)
        case Resume           ⇒ finishShellRegistration()
      }
    if (!shortCircuitBuffer.isEmpty && currentLimit == 0) self ! Resume
  }

  private def processEvent(b: BoundaryEvent): Unit = {
    val shell = b.shell

    if (!shell.isTerminated && (shell.isInitialized || tryInit(shell))) {
      try currentLimit = shell.processEvent(b, currentLimit)
      catch {
        case NonFatal(e) ⇒ shell.tryAbort(e)
      }

      if (shell.isTerminated) {
        activeInterpreters -= shell
        if (activeInterpreters.isEmpty && newShells.isEmpty) context.stop(self)
      }
    }
  }

  override def receive: Receive = {
    case b: BoundaryEvent ⇒
      currentLimit = eventLimit
      processEvent(b)
      if (shortCircuitBuffer != null) shortCircuitBatch()

    case Resume ⇒
      currentLimit = eventLimit
      if (shortCircuitBuffer != null) shortCircuitBatch()

    case StreamSupervisor.PrintDebugDump ⇒
      val builder = new java.lang.StringBuilder(s"activeShells (actor: $self):\n")
      activeInterpreters.foreach { shell ⇒
        builder.append("  ")
          .append(shell.toString.replace("\n", "\n  "))
          .append("\n")
        if (shell.isInitialized) {
          builder.append(shell.interpreter.toString)
        } else {
          builder.append("    Not initialized")
        }
        builder.append("\n")
      }
      builder.append(s"newShells:\n")
      newShells.foreach { shell ⇒
        builder.append("  ")
          .append(shell.toString.replace("\n", "\n  "))
          .append("\n")
        if (shell.isInitialized) {
          builder.append(shell.interpreter.toString)
        } else {
          builder.append("    Not initialized")
        }
        builder.append("\n")
      }
      println(builder)
  }

  override def postStop(): Unit = {
    val ex = AbruptTerminationException(self)
    activeInterpreters.foreach(_.tryAbort(ex))
    activeInterpreters = Set.empty[GraphInterpreterShell]
    newShells.foreach(s ⇒ if (tryInit(s)) s.tryAbort(ex))
  }
}
