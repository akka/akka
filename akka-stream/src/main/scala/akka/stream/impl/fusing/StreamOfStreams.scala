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
object PrefixAndTail {

  sealed trait MaterializationState
  case object NotMaterialized extends MaterializationState
  case object AlreadyMaterialized extends MaterializationState
  case object TimedOut extends MaterializationState

  case object NormalCompletion extends MaterializationState
  case class FailureCompletion(ex: Throwable) extends MaterializationState

  trait TailInterface[T] {
    def pushSubstream(elem: T): Unit
    def completeSubstream(): Unit
    def failSubstream(ex: Throwable)
  }

  final class TailSource[T](
    timeout: FiniteDuration,
    register: TailInterface[T] ⇒ Unit,
    pullParent: Unit ⇒ Unit,
    cancelParent: Unit ⇒ Unit) extends GraphStage[SourceShape[T]] {
    val out: Outlet[T] = Outlet("Tail.out")
    val materializationState = new AtomicReference[MaterializationState](NotMaterialized)
    override val shape: SourceShape[T] = SourceShape(out)

    private final class TailSourceLogic(_shape: Shape) extends GraphStageLogic(_shape) with OutHandler with TailInterface[T] {
      setHandler(out, this)

      override def preStart(): Unit = {
        materializationState.getAndSet(AlreadyMaterialized) match {
          case AlreadyMaterialized ⇒
            failStage(new IllegalStateException("Tail Source cannot be materialized more than once."))
          case TimedOut ⇒
            // Already detached from parent
            failStage(new SubscriptionTimeoutException(s"Tail Source has not been materialized in $timeout."))
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

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TailSourceLogic(shape)
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
    import PrefixAndTail._

    private var left = if (n < 0) 0 else n
    private var builder = Vector.newBuilder[T]
    private var tailSource: TailSource[T] = null
    private var tail: TailInterface[T] = null
    builder.sizeHint(left)
    private var pendingCompletion: MaterializationState = null

    private val SubscriptionTimer = "SubstreamSubscriptionTimer"

    private val onSubstreamPull = getAsyncCallback[Unit](_ ⇒ pull(in))
    private val onSubstreamFinish = getAsyncCallback[Unit](_ ⇒ completeStage())
    private val onSubstreamRegister = getAsyncCallback[TailInterface[T]] { tailIf ⇒
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
      tailSource = new TailSource[T](timeout, onSubstreamRegister.invoke, onSubstreamPull.invoke, onSubstreamFinish.invoke)
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
