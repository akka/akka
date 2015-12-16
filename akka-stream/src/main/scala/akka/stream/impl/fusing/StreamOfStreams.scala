/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.concurrent.atomic.AtomicReference

import akka.stream._
import akka.stream.impl.SubscriptionTimeoutException
import akka.stream.stage._
import akka.stream.scaladsl._
import akka.stream.actor.ActorSubscriberMessage
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.actor.ActorPublisherMessage
import akka.stream.actor.ActorPublisherMessage._
import java.{ util ⇒ ju }
import scala.collection.immutable
import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
final class FlattenMerge[T, M](breadth: Int) extends GraphStage[FlowShape[Graph[SourceShape[T], M], T]] {
  private val in = Inlet[Graph[SourceShape[T], M]]("flatten.in")
  private val out = Outlet[T]("flatten.out")

  override def initialAttributes = Attributes.name("FlattenMerge")
  override val shape = FlowShape(in, out)

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {

    import StreamOfStreams.{ LocalSink, LocalSource }

    var sources = Set.empty[LocalSource[T]]
    def activeSources = sources.size

    private sealed trait Queue {
      def hasData: Boolean
      def enqueue(src: LocalSource[T]): Unit
      def dequeue(): LocalSource[T]
    }

    private final class FixedQueue extends Queue {
      final val Size = 16
      final val Mask = 15

      private val queue = new Array[LocalSource[T]](Size)
      private var head = 0
      private var tail = 0

      def hasData = tail != head
      def enqueue(src: LocalSource[T]): Unit =
        if (tail - head == Size) {
          val queue = new DynamicQueue
          while (hasData) {
            queue.add(dequeue())
          }
          queue.add(src)
          q = queue
        } else {
          queue(tail & Mask) = src
          tail += 1
        }
      def dequeue(): LocalSource[T] = {
        val ret = queue(head & Mask)
        head += 1
        ret
      }
    }

    private final class DynamicQueue extends ju.LinkedList[LocalSource[T]] with Queue {
      def hasData = !isEmpty()
      def enqueue(src: LocalSource[T]): Unit = add(src)
      def dequeue(): LocalSource[T] = remove()
    }

    private var q: Queue = new FixedQueue

    def pushOut(): Unit = {
      val src = q.dequeue()
      push(out, src.elem)
      src.elem = null.asInstanceOf[T]
      if (src.isActive) src.pull()
      else removeSource(src)
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val source = grab(in)
        addSource(source)
        if (activeSources < breadth) tryPull(in)
      }
      override def onUpstreamFinish(): Unit = if (activeSources == 0) completeStage()
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        pull(in)
        setHandler(out, outHandler)
      }
    })

    val outHandler = new OutHandler {
      // could be unavailable due to async input having been executed before this notification
      override def onPull(): Unit = if (q.hasData && isAvailable(out)) pushOut()
    }

    def addSource(source: Graph[SourceShape[T], M]): Unit = {
      val localSource = new LocalSource[T]()
      sources += localSource
      val subF = Source.fromGraph(source)
        .runWith(new LocalSink(getAsyncCallback[ActorSubscriberMessage] {
          case OnNext(elem) ⇒
            val elemT = elem.asInstanceOf[T]
            if (isAvailable(out)) {
              push(out, elemT)
              localSource.pull()
            } else {
              localSource.elem = elemT
              q.enqueue(localSource)
            }
          case OnComplete ⇒
            localSource.deactivate()
            if (localSource.elem == null) removeSource(localSource)
          case OnError(ex) ⇒
            failStage(ex)
        }.invoke))(interpreter.subFusingMaterializer)
      localSource.activate(subF)
    }

    def removeSource(src: LocalSource[T]): Unit = {
      val pullSuppressed = activeSources == breadth
      sources -= src
      if (pullSuppressed) tryPull(in)
      if (activeSources == 0 && isClosed(in)) completeStage()
    }

    override def postStop(): Unit = {
      sources.foreach(_.cancel())
    }
  }

  override def toString: String = s"FlattenMerge($breadth)"
}

/**
 * INTERNAL API
 */
private[fusing] object StreamOfStreams {
  import akka.dispatch.ExecutionContexts.sameThreadExecutionContext
  private val RequestOne = Request(1) // No need to frivolously allocate these
  private type LocalSinkSubscription = ActorPublisherMessage ⇒ Unit
  /**
   * INTERNAL API
   */
  private[fusing] final class LocalSource[T] {
    private var subF: Future[LocalSinkSubscription] = _
    private var sub: LocalSinkSubscription = _

    var elem: T = null.asInstanceOf[T]

    def isActive: Boolean = sub ne null

    def deactivate(): Unit = {
      sub = null
      subF = null
    }

    def activate(f: Future[LocalSinkSubscription]): Unit = {
      subF = f
      /*
       * The subscription is communicated to the FlattenMerge stage by way of completing
       * the future. Encoding it like this means that the `sub` field will be written
       * either by us (if the future has already been completed) or by the LocalSink (when
       * it eventually completes the future in its `preStart`). The important part is that
       * either way the `sub` field is populated before we get the first `OnNext` message
       * and the value is safely published in either case as well (since AsyncCallback is
       * based on an Actor message send).
       */
      f.foreach(s ⇒ sub = s)(sameThreadExecutionContext)
    }

    def pull(): Unit = {
      if (sub ne null) sub(RequestOne)
      else if (subF eq null) throw new IllegalStateException("not yet initialized, subscription future not set")
      else throw new IllegalStateException("not yet initialized, subscription future has " + subF.value)
    }

    def cancel(): Unit =
      if (subF ne null)
        subF.foreach(_(Cancel))(sameThreadExecutionContext)
  }

  /**
   * INTERNAL API
   */
  private[fusing] final class LocalSink[T](notifier: ActorSubscriberMessage ⇒ Unit)
    extends GraphStageWithMaterializedValue[SinkShape[T], Future[LocalSinkSubscription]] {

    private val in = Inlet[T]("LocalSink.in")

    override def initialAttributes = Attributes.name("LocalSink")
    override val shape = SinkShape(in)

    override def createLogicAndMaterializedValue(attr: Attributes): (GraphStageLogic, Future[LocalSinkSubscription]) = {
      val sub = Promise[LocalSinkSubscription]
      val logic = new GraphStageLogic(shape) {
        setHandler(in, new InHandler {
          override def onPush(): Unit = notifier(OnNext(grab(in)))

          override def onUpstreamFinish(): Unit = notifier(OnComplete)

          override def onUpstreamFailure(ex: Throwable): Unit = notifier(OnError(ex))
        })

        override def preStart(): Unit = {
          pull(in)
          sub.success(
            getAsyncCallback[ActorPublisherMessage] {
              case RequestOne ⇒ tryPull(in)
              case Cancel     ⇒ completeStage()
              case _          ⇒ throw new IllegalStateException("Bug")
            }.invoke)
        }
      }
      logic -> sub.future
    }
  }
}

/**
 * INTERNAL API
 */
object SubSource {

  sealed trait MaterializationState
  case object NotMaterialized extends MaterializationState
  case object AlreadyMaterialized extends MaterializationState
  case object TimedOut extends MaterializationState

  case object NormalCompletion extends MaterializationState
  case class FailureCompletion(ex: Throwable) extends MaterializationState

  trait SubSourceInterface[T] {
    def pushSubstream(elem: T): Unit
    def completeSubstream(): Unit
    def failSubstream(ex: Throwable)
  }

  final class SubSource[T](
    timeout: FiniteDuration,
    register: SubSourceInterface[T] ⇒ Unit,
    pullParent: Unit ⇒ Unit,
    cancelParent: Unit ⇒ Unit) extends GraphStage[SourceShape[T]] {
    val out: Outlet[T] = Outlet("Tail.out")
    val materializationState = new AtomicReference[MaterializationState](NotMaterialized)
    override val shape: SourceShape[T] = SourceShape(out)

    private final class SubSourceLogic(_shape: Shape) extends GraphStageLogic(_shape) with OutHandler with SubSourceInterface[T] {
      setHandler(out, this)

      override def preStart(): Unit = {
        materializationState.getAndSet(AlreadyMaterialized) match {
          case AlreadyMaterialized ⇒
            failStage(new IllegalStateException("Substream Source cannot be materialized more than once."))
          case TimedOut ⇒
            // Already detached from parent
            failStage(new SubscriptionTimeoutException(s"Substream Source has not been materialized in $timeout."))
          case NormalCompletion ⇒
            // Already detached from parent
            completeStage()
          case FailureCompletion(ex) ⇒
            // Already detached from parent
            failStage(ex)
          case NotMaterialized ⇒
            register(this)
        }

      }

      private val onParentPush = getAsyncCallback[T](push(out, _))
      private val onParentFinish = getAsyncCallback[Unit](_ ⇒ completeStage())
      private val onParentFailure = getAsyncCallback[Throwable](failStage)

      override def pushSubstream(elem: T): Unit = onParentPush.invoke(elem)
      override def completeSubstream(): Unit = onParentFinish.invoke(())
      override def failSubstream(ex: Throwable): Unit = onParentFailure.invoke(ex)

      override def onPull(): Unit = pullParent(())
      override def onDownstreamFinish(): Unit = cancelParent(())
    }

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new SubSourceLogic(shape)
  }

}

/**
 * INTERNAL API
 */
final class PrefixAndTail[T](n: Int) extends GraphStage[FlowShape[T, (immutable.Seq[T], Source[T, Unit])]] {
  val in: Inlet[T] = Inlet("PrefixAndTail.in")
  val out: Outlet[(immutable.Seq[T], Source[T, Unit])] = Outlet("PrefixAndTail.out")
  override val shape: FlowShape[T, (immutable.Seq[T], Source[T, Unit])] = FlowShape(in, out)

  override def initialAttributes = Attributes.name("PrefixAndTail")

  private final class PrefixAndTailLogic(_shape: Shape) extends TimerGraphStageLogic(_shape) with OutHandler with InHandler {
    import SubSource._

    private var left = if (n < 0) 0 else n
    private var builder = Vector.newBuilder[T]
    private var tailSource: SubSource[T] = null
    private var tail: SubSourceInterface[T] = null
    builder.sizeHint(left)
    private var pendingCompletion: MaterializationState = null

    private val SubscriptionTimer = "SubstreamSubscriptionTimer"

    private val onSubstreamPull = getAsyncCallback[Unit](_ ⇒ pull(in))
    private val onSubstreamFinish = getAsyncCallback[Unit](_ ⇒ completeStage())
    private val onSubstreamRegister = getAsyncCallback[SubSourceInterface[T]] { tailIf ⇒
      tail = tailIf
      cancelTimer(SubscriptionTimer)
      pendingCompletion match {
        case NormalCompletion ⇒
          tail.completeSubstream()
          completeStage()
        case FailureCompletion(ex) ⇒
          tail.failSubstream(ex)
          completeStage()
        case _ ⇒
      }
    }

    override protected def onTimer(timerKey: Any): Unit =
      if (tailSource.materializationState.compareAndSet(NotMaterialized, TimedOut)) completeStage()

    private def prefixComplete = builder eq null
    private def waitingSubstreamRegistration = tail eq null

    private def openSubstream(): Source[T, Unit] = {
      val timeout = ActorMaterializer.downcast(interpreter.materializer).settings.subscriptionTimeoutSettings.timeout
      tailSource = new SubSource[T](timeout, onSubstreamRegister.invoke, onSubstreamPull.invoke, onSubstreamFinish.invoke)
      scheduleOnce(SubscriptionTimer, timeout)
      builder = null
      Source.fromGraph(tailSource)
    }

    // Needs to keep alive if upstream completes but substream has been not yet materialized
    override def keepGoingAfterAllPortsClosed: Boolean = true

    override def onPush(): Unit = {
      if (prefixComplete) {
        tail.pushSubstream(grab(in))
      } else {
        builder += grab(in)
        left -= 1
        if (left == 0) {
          push(out, (builder.result(), openSubstream()))
          complete(out)
        } else pull(in)
      }
    }
    override def onPull(): Unit = {
      if (left == 0) {
        push(out, (Nil, openSubstream()))
        complete(out)
      } else pull(in)
    }

    override def onUpstreamFinish(): Unit = {
      if (!prefixComplete) {
        // This handles the unpulled out case as well
        emit(out, (builder.result, Source.empty), () ⇒ completeStage())
      } else {
        if (waitingSubstreamRegistration) {
          // Detach if possible.
          // This allows this stage to complete without waiting for the substream to be materialized, since that
          // is empty anyway. If it is already being registered (state was not NotMaterialized) then we will be
          // able to signal completion normally soon.
          if (tailSource.materializationState.compareAndSet(NotMaterialized, NormalCompletion)) completeStage()
          else pendingCompletion = NormalCompletion
        } else {
          tail.completeSubstream()
          completeStage()
        }
      }
    }

    override def onUpstreamFailure(ex: Throwable): Unit = {
      if (prefixComplete) {
        if (waitingSubstreamRegistration) {
          // Detach if possible.
          // This allows this stage to complete without waiting for the substream to be materialized, since that
          // is empty anyway. If it is already being registered (state was not NotMaterialized) then we will be
          // able to signal completion normally soon.
          if (tailSource.materializationState.compareAndSet(NotMaterialized, FailureCompletion(ex))) failStage(ex)
          else pendingCompletion = FailureCompletion(ex)
        } else {
          tail.failSubstream(ex)
          completeStage()
        }
      } else failStage(ex)
    }

    override def onDownstreamFinish(): Unit = {
      if (!prefixComplete) completeStage()
      // Otherwise substream is open, ignore
    }

    setHandler(in, this)
    setHandler(out, this)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new PrefixAndTailLogic(shape)

  override def toString: String = s"PrefixAndTail($n)"
}

/**
 * INERNAL API
 */
object Split {
  sealed abstract class SplitDecision

  /** Splits before the current element. The current element will be the first element in the new substream. */
  case object SplitBefore extends SplitDecision

  /** Splits after the current element. The current element will be the last element in the current substream. */
  case object SplitAfter extends SplitDecision

  def when[T](p: T ⇒ Boolean): Graph[FlowShape[T, Source[T, Unit]], Unit] = new Split(Split.SplitBefore, p)
  def after[T](p: T ⇒ Boolean): Graph[FlowShape[T, Source[T, Unit]], Unit] = new Split(Split.SplitAfter, p)
}

/**
 * INERNAL API
 */
final class Split[T](decision: Split.SplitDecision, p: T ⇒ Boolean) extends GraphStage[FlowShape[T, Source[T, Unit]]] {
  val in: Inlet[T] = Inlet("Split.in")
  val out: Outlet[Source[T, Unit]] = Outlet("Split.out")
  override val shape: FlowShape[T, Source[T, Unit]] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    import SubSource._
    import Split._
    private val SubscriptionTimer = "SubstreamSubscriptionTimer"
    private var timeout: FiniteDuration = _
    private var substreamSource: SubSource[T] = null
    private var substreamPushed = false
    private var substreamCancelled = false

    override def preStart(): Unit = {
      timeout = ActorMaterializer.downcast(interpreter.materializer).settings.subscriptionTimeoutSettings.timeout
    }

    override def keepGoingAfterAllPortsClosed: Boolean = true

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (substreamSource eq null) pull(in)
        else if (!substreamPushed) {
          push(out, Source.fromGraph(substreamSource))
          substreamPushed = true
        }
      }

      override def onDownstreamFinish(): Unit = {
        // If the substream is already cancelled or it has not been handed out, we can go away
        if (!substreamPushed || substreamCancelled) completeStage()
      }
    })

    // initial input handler
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val handler = new SubstreamHandler
        val elem = grab(in)

        decision match {
          case SplitAfter if p(elem) ⇒
            push(out, Source.single(elem))
          // Next pull will come from the next substream that we will open
          case _ ⇒
            handler.firstElem = elem
        }

        handOver(handler)
      }

      override def onUpstreamFinish(): Unit = completeStage()

    })

    private def handOver(handler: SubstreamHandler): Unit = {
      if (isClosed(out)) completeStage()
      else {
        val source = new SubSource[T](
          timeout,
          handler.onSubstreamRegister.invoke,
          handler.onSubstreamPull.invoke,
          handler.onSubstreamFinish.invoke)
        substreamSource = source
        substreamCancelled = false
        setHandler(in, handler)

        if (isAvailable(out)) {
          push(out, Source.fromGraph(source))
          substreamPushed = true
        } else substreamPushed = false

      }
    }

    override protected def onTimer(timerKey: Any): Unit =
      if (substreamSource.materializationState.compareAndSet(NotMaterialized, TimedOut))
        failStage(new SubscriptionTimeoutException(s"Substream Source has not been materialized in $timeout."))

    private class SubstreamHandler extends InHandler {
      var firstElem: T = null.asInstanceOf[T]
      private var substream: SubSourceInterface[T] = null
      private var pendingCompletion: MaterializationState = null
      private var willCompleteAfterInitialElement = false
      // The interpreter handles ignoring events for a port after it has been closed, but this is not a real
      // port but an async side-channel. We need to ignore it ourselves.
      private var ignoreAsync = false

      private def waitingSubstreamRegistration: Boolean = substream eq null
      private def hasInitialElement: Boolean = firstElem.asInstanceOf[AnyRef] ne null

      // Substreams are always assumed to be pushable position when we enter this method
      private def closeThis(handler: SubstreamHandler, currentElem: T): Unit = {
        ignoreAsync = true
        decision match {
          case SplitAfter ⇒
            if (!substreamCancelled) {
              substream.pushSubstream(currentElem)
              substream.completeSubstream()
            }
          case SplitBefore ⇒
            handler.firstElem = currentElem
            if (!substreamCancelled) substream.completeSubstream()
        }
      }

      val onSubstreamPull = getAsyncCallback[Unit] { _ ⇒
        if (!ignoreAsync) {
          if (hasInitialElement) {
            substream.pushSubstream(firstElem)
            firstElem = null.asInstanceOf[T]
            if (willCompleteAfterInitialElement) {
              substream.completeSubstream()
              ignoreAsync = true
              completeStage()
            }
          } else pull(in)
        }
      }

      val onSubstreamFinish = getAsyncCallback[Unit] { _ ⇒
        if (!ignoreAsync) {
          substreamCancelled = true
          ignoreAsync = true
          // Start draining
          if (!hasBeenPulled(in)) pull(in)
        }
      }

      val onSubstreamRegister = getAsyncCallback[SubSourceInterface[T]] { substreamIf ⇒
        substream = substreamIf
        cancelTimer(SubscriptionTimer)

        // This is not set if willCompleteAfterInitialElement is true so we can close here safely
        pendingCompletion match {
          case NormalCompletion ⇒
            substream.completeSubstream()
            ignoreAsync = true
            completeStage()
          case FailureCompletion(ex) ⇒
            substream.failSubstream(ex)
            ignoreAsync = true
            failStage(ex)
          case _ ⇒
        }
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        try {
          if (p(elem)) {
            val handler = new SubstreamHandler
            closeThis(handler, elem)
            handOver(handler)
          } else {
            // Drain into the void
            if (substreamCancelled) pull(in)
            else substream.pushSubstream(elem)
          }
        } catch {
          case NonFatal(ex) ⇒ onUpstreamFailure(ex)
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (hasInitialElement) willCompleteAfterInitialElement = true
        else {
          if (waitingSubstreamRegistration) {
            // Detach if possible.
            // This allows this stage to complete without waiting for the substream to be materialized, since that
            // is empty anyway. If it is already being registered (state was not NotMaterialized) then we will be
            // able to signal completion normally soon.
            if (substreamSource.materializationState.compareAndSet(NotMaterialized, NormalCompletion)) completeStage()
            else pendingCompletion = NormalCompletion
          } else {
            substream.completeSubstream()
            ignoreAsync = true
            completeStage()
          }

        }
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        if (waitingSubstreamRegistration) {
          // Detach if possible.
          // This allows this stage to complete without waiting for the substream to be materialized, since that
          // is empty anyway. If it is already being registered (state was not NotMaterialized) then we will be
          // able to signal completion normally soon.
          if (substreamSource.materializationState.compareAndSet(NotMaterialized, FailureCompletion(ex))) failStage(ex)
          else pendingCompletion = FailureCompletion(ex)
        } else {
          substream.failSubstream(ex)
          ignoreAsync = true
          failStage(ex)
        }
      }

    }
  }
}