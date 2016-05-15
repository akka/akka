/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import java.util.concurrent.atomic.AtomicReference
import akka.NotUsed
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.SubscriptionTimeoutException
import akka.stream.stage._
import akka.stream.scaladsl._
import akka.stream.actor.ActorSubscriberMessage
import scala.collection.{ mutable, immutable }
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.annotation.tailrec
import akka.stream.impl.PublisherSource
import akka.stream.impl.CancellingSubscriber
import akka.stream.impl.{ Buffer ⇒ BufferImpl }
import scala.collection.JavaConversions._

/**
 * INTERNAL API
 */
final class FlattenMerge[T, M](breadth: Int) extends GraphStage[FlowShape[Graph[SourceShape[T], M], T]] {
  private val in = Inlet[Graph[SourceShape[T], M]]("flatten.in")
  private val out = Outlet[T]("flatten.out")

  override def initialAttributes = DefaultAttributes.flattenMerge
  override val shape = FlowShape(in, out)

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {

    var sources = Set.empty[SubSinkInlet[T]]
    def activeSources = sources.size

    var q: BufferImpl[SubSinkInlet[T]] = _

    override def preStart(): Unit = q = BufferImpl(breadth, materializer)

    def pushOut(): Unit = {
      val src = q.dequeue()
      push(out, src.grab())
      if (!src.isClosed) src.pull()
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
      override def onPull(): Unit = if (q.nonEmpty && isAvailable(out)) pushOut()
    }

    def addSource(source: Graph[SourceShape[T], M]): Unit = {
      val sinkIn = new SubSinkInlet[T]("FlattenMergeSink")
      sinkIn.setHandler(new InHandler {
        override def onPush(): Unit = {
          if (isAvailable(out)) {
            push(out, sinkIn.grab())
            sinkIn.pull()
          } else {
            q.enqueue(sinkIn)
          }
        }
        override def onUpstreamFinish(): Unit = if (!sinkIn.isAvailable) removeSource(sinkIn)
      })
      sinkIn.pull()
      sources += sinkIn
      Source.fromGraph(source).runWith(sinkIn.sink)(interpreter.subFusingMaterializer)
    }

    def removeSource(src: SubSinkInlet[T]): Unit = {
      val pullSuppressed = activeSources == breadth
      sources -= src
      if (pullSuppressed) tryPull(in)
      if (activeSources == 0 && isClosed(in)) completeStage()
    }

    override def postStop(): Unit = sources.foreach(_.cancel())

  }

  override def toString: String = s"FlattenMerge($breadth)"
}

/**
 * INTERNAL API
 */
final class PrefixAndTail[T](n: Int) extends GraphStage[FlowShape[T, (immutable.Seq[T], Source[T, NotUsed])]] {
  val in: Inlet[T] = Inlet("PrefixAndTail.in")
  val out: Outlet[(immutable.Seq[T], Source[T, NotUsed])] = Outlet("PrefixAndTail.out")
  override val shape: FlowShape[T, (immutable.Seq[T], Source[T, NotUsed])] = FlowShape(in, out)

  override def initialAttributes = DefaultAttributes.prefixAndTail

  private final class PrefixAndTailLogic(_shape: Shape) extends TimerGraphStageLogic(_shape) with OutHandler with InHandler {

    private var left = if (n < 0) 0 else n
    private var builder = Vector.newBuilder[T]
    builder.sizeHint(left)

    private var tailSource: SubSourceOutlet[T] = null

    private val SubscriptionTimer = "SubstreamSubscriptionTimer"

    override protected def onTimer(timerKey: Any): Unit = {
      val materializer = ActorMaterializer.downcast(interpreter.materializer)
      val timeoutSettings = materializer.settings.subscriptionTimeoutSettings
      val timeout = timeoutSettings.timeout

      timeoutSettings.mode match {
        case StreamSubscriptionTimeoutTerminationMode.CancelTermination ⇒
          tailSource.timeout(timeout)
          if (tailSource.isClosed) completeStage()
        case StreamSubscriptionTimeoutTerminationMode.NoopTermination ⇒
        // do nothing
        case StreamSubscriptionTimeoutTerminationMode.WarnTermination ⇒
          materializer.logger.warning("Substream subscription timeout triggered after {} in prefixAndTail({}).", timeout, n)
      }
    }

    private def prefixComplete = builder eq null

    private def subHandler = new OutHandler {
      override def onPull(): Unit = {
        setKeepGoing(false)
        cancelTimer(SubscriptionTimer)
        pull(in)
        tailSource.setHandler(new OutHandler {
          override def onPull(): Unit = pull(in)
        })
      }
    }

    private def openSubstream(): Source[T, NotUsed] = {
      val timeout = ActorMaterializer.downcast(interpreter.materializer).settings.subscriptionTimeoutSettings.timeout
      tailSource = new SubSourceOutlet[T]("TailSource")
      tailSource.setHandler(subHandler)
      setKeepGoing(true)
      scheduleOnce(SubscriptionTimer, timeout)
      builder = null
      Source.fromGraph(tailSource.source)
    }

    override def onPush(): Unit = {
      if (prefixComplete) {
        tailSource.push(grab(in))
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
        if (!tailSource.isClosed) tailSource.complete()
        completeStage()
      }
    }

    override def onUpstreamFailure(ex: Throwable): Unit = {
      if (prefixComplete) {
        if (!tailSource.isClosed) tailSource.fail(ex)
        completeStage()
      } else failStage(ex)
    }

    override def onDownstreamFinish(): Unit = {
      if (!prefixComplete) completeStage()
      // Otherwise substream is open, ignore
    }

    setHandlers(in, out, this)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new PrefixAndTailLogic(shape)

  override def toString: String = s"PrefixAndTail($n)"
}

/**
 * INTERNAL API
 */
final class GroupBy[T, K](maxSubstreams: Int, keyFor: T ⇒ K) extends GraphStage[FlowShape[T, Source[T, NotUsed]]] {
  val in: Inlet[T] = Inlet("GroupBy.in")
  val out: Outlet[Source[T, NotUsed]] = Outlet("GroupBy.out")
  override val shape: FlowShape[T, Source[T, NotUsed]] = FlowShape(in, out)
  override def initialAttributes = DefaultAttributes.groupBy

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with OutHandler with InHandler {
    parent ⇒
    lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
    private val activeSubstreamsMap = new java.util.HashMap[Any, SubstreamSource]()
    private val closedSubstreams = new java.util.HashSet[Any]()
    private var timeout: FiniteDuration = _
    private var substreamWaitingToBePushed: Option[SubstreamSource] = None
    private var nextElementKey: K = null.asInstanceOf[K]
    private var nextElementValue: T = null.asInstanceOf[T]
    private var _nextId = 0
    private val substreamsJustStared = new java.util.HashSet[Any]()
    private var firstPushCounter: Int = 0

    private def nextId(): Long = { _nextId += 1; _nextId }

    private def hasNextElement = nextElementKey != null

    private def clearNextElement(): Unit = {
      nextElementKey = null.asInstanceOf[K]
      nextElementValue = null.asInstanceOf[T]
    }

    private def tryCompleteAll(): Boolean =
      if (activeSubstreamsMap.isEmpty || (!hasNextElement && firstPushCounter == 0)) {
        for (value ← activeSubstreamsMap.values()) value.complete()
        completeStage()
        true
      } else false

    private def fail(ex: Throwable): Unit = {
      for (value ← activeSubstreamsMap.values()) value.fail(ex)
      failStage(ex)
    }

    private def needToPull: Boolean = !(hasBeenPulled(in) || isClosed(in) || hasNextElement)

    override def preStart(): Unit =
      timeout = ActorMaterializer.downcast(interpreter.materializer).settings.subscriptionTimeoutSettings.timeout

    override def onPull(): Unit = {
      substreamWaitingToBePushed match {
        case Some(substreamSource) ⇒
          push(out, Source.fromGraph(substreamSource.source))
          scheduleOnce(substreamSource.key, timeout)
          substreamWaitingToBePushed = None
        case None ⇒
          if (hasNextElement) {
            val subSubstreamSource = activeSubstreamsMap.get(nextElementKey)
            if (subSubstreamSource.isAvailable) {
              subSubstreamSource.push(nextElementValue)
              clearNextElement()
            }
          } else tryPull(in)
      }
    }

    override def onUpstreamFailure(ex: Throwable): Unit = fail(ex)

    override def onDownstreamFinish(): Unit =
      if (activeSubstreamsMap.isEmpty) completeStage() else setKeepGoing(true)

    override def onPush(): Unit = try {
      val elem = grab(in)
      val key = keyFor(elem)
      require(key != null, "Key cannot be null")
      val substreamSource = activeSubstreamsMap.get(key)
      if (substreamSource != null) {
        if (substreamSource.isAvailable) substreamSource.push(elem)
        else {
          nextElementKey = key
          nextElementValue = elem
        }
      } else {
        if (activeSubstreamsMap.size == maxSubstreams)
          fail(new IllegalStateException(s"Cannot open substream for key '$key': too many substreams open"))
        else if (closedSubstreams.contains(key) && !hasBeenPulled(in))
          pull(in)
        else runSubstream(key, elem)
      }
    } catch {
      case NonFatal(ex) ⇒
        decider(ex) match {
          case Supervision.Stop                         ⇒ fail(ex)
          case Supervision.Resume | Supervision.Restart ⇒ if (!hasBeenPulled(in)) pull(in)
        }
    }

    override def onUpstreamFinish(): Unit = {
      if (!tryCompleteAll()) setKeepGoing(true)
    }

    private def runSubstream(key: K, value: T): Unit = {
      val substreamSource = new SubstreamSource("GroupBySource " + nextId, key, value)
      activeSubstreamsMap.put(key, substreamSource)
      firstPushCounter += 1
      if (isAvailable(out)) {
        push(out, Source.fromGraph(substreamSource.source))
        scheduleOnce(key, timeout)
        substreamWaitingToBePushed = None
      } else {
        setKeepGoing(true)
        substreamsJustStared.add(substreamSource)
        substreamWaitingToBePushed = Some(substreamSource)
      }
    }

    override protected def onTimer(timerKey: Any): Unit = {
      val substreamSource = activeSubstreamsMap.get(timerKey)
      if (substreamSource != null) {
        substreamSource.timeout(timeout)
        closedSubstreams.add(timerKey)
        activeSubstreamsMap.remove(timerKey)
        if (isClosed(in)) tryCompleteAll()
      }
    }

    setHandlers(in, out, this)

    private class SubstreamSource(name: String, val key: K, var firstElement: T) extends SubSourceOutlet[T](name) with OutHandler {
      def firstPush(): Boolean = firstElement != null
      def hasNextForSubSource = hasNextElement && nextElementKey == key
      private def completeSubStream(): Unit = {
        complete()
        activeSubstreamsMap.remove(key)
        closedSubstreams.add(key)
      }

      private def tryCompleteHandler(): Unit = {
        if (parent.isClosed(in) && !hasNextForSubSource) {
          completeSubStream()
          tryCompleteAll()
        }
      }

      override def onPull(): Unit = {
        cancelTimer(key)
        if (firstPush) {
          firstPushCounter -= 1
          push(firstElement)
          firstElement = null.asInstanceOf[T]
          substreamsJustStared.remove(this)
          if (substreamsJustStared.isEmpty) setKeepGoing(false)
        } else if (hasNextForSubSource) {
          push(nextElementValue)
          clearNextElement()
        } else if (needToPull) pull(in)

        tryCompleteHandler()
      }

      override def onDownstreamFinish(): Unit = {
        if (hasNextElement && nextElementKey == key) clearNextElement()
        if (firstPush()) firstPushCounter -= 1
        completeSubStream()
        if (parent.isClosed(in)) tryCompleteAll() else if (needToPull) pull(in)
      }

      setHandler(this)
    }
  }

  override def toString: String = "GroupBy"

}
/**
 * INTERNAL API
 */
object Split {
  sealed abstract class SplitDecision

  /** Splits before the current element. The current element will be the first element in the new substream. */
  case object SplitBefore extends SplitDecision

  /** Splits after the current element. The current element will be the last element in the current substream. */
  case object SplitAfter extends SplitDecision

  def when[T](p: T ⇒ Boolean, substreamCancelStrategy: SubstreamCancelStrategy): Graph[FlowShape[T, Source[T, NotUsed]], NotUsed] =
    new Split(Split.SplitBefore, p, substreamCancelStrategy)

  def after[T](p: T ⇒ Boolean, substreamCancelStrategy: SubstreamCancelStrategy): Graph[FlowShape[T, Source[T, NotUsed]], NotUsed] =
    new Split(Split.SplitAfter, p, substreamCancelStrategy)
}

/**
 * INTERNAL API
 */
final class Split[T](decision: Split.SplitDecision, p: T ⇒ Boolean, substreamCancelStrategy: SubstreamCancelStrategy) extends GraphStage[FlowShape[T, Source[T, NotUsed]]] {
  val in: Inlet[T] = Inlet("Split.in")
  val out: Outlet[Source[T, NotUsed]] = Outlet("Split.out")
  override val shape: FlowShape[T, Source[T, NotUsed]] = FlowShape(in, out)

  private val propagateSubstreamCancel = substreamCancelStrategy match {
    case SubstreamCancelStrategies.Propagate ⇒ true
    case SubstreamCancelStrategies.Drain     ⇒ false
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    import Split._

    private val SubscriptionTimer = "SubstreamSubscriptionTimer"

    private var timeout: FiniteDuration = _
    private var substreamSource: SubSourceOutlet[T] = null
    private var substreamWaitingToBePushed = false
    private var substreamCancelled = false

    override def preStart(): Unit = {
      timeout = ActorMaterializer.downcast(interpreter.materializer).settings.subscriptionTimeoutSettings.timeout
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (substreamSource eq null) pull(in)
        else if (!substreamWaitingToBePushed) {
          push(out, Source.fromGraph(substreamSource.source))
          scheduleOnce(SubscriptionTimer, timeout)
          substreamWaitingToBePushed = true
        }
      }

      override def onDownstreamFinish(): Unit = {
        // If the substream is already cancelled or it has not been handed out, we can go away
        if (!substreamWaitingToBePushed || substreamCancelled) completeStage()
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
        substreamSource = new SubSourceOutlet[T]("SplitSource")
        substreamSource.setHandler(handler)
        substreamCancelled = false
        setHandler(in, handler)
        setKeepGoing(enabled = handler.hasInitialElement)

        if (isAvailable(out)) {
          push(out, Source.fromGraph(substreamSource.source))
          scheduleOnce(SubscriptionTimer, timeout)
          substreamWaitingToBePushed = true
        } else substreamWaitingToBePushed = false
      }
    }

    override protected def onTimer(timerKey: Any): Unit = substreamSource.timeout(timeout)

    private class SubstreamHandler extends InHandler with OutHandler {

      var firstElem: T = null.asInstanceOf[T]

      def hasInitialElement: Boolean = firstElem.asInstanceOf[AnyRef] ne null
      private var willCompleteAfterInitialElement = false

      // Substreams are always assumed to be pushable position when we enter this method
      private def closeThis(handler: SubstreamHandler, currentElem: T): Unit = {
        decision match {
          case SplitAfter ⇒
            if (!substreamCancelled) {
              substreamSource.push(currentElem)
              substreamSource.complete()
            }
          case SplitBefore ⇒
            handler.firstElem = currentElem
            if (!substreamCancelled) substreamSource.complete()
        }
      }

      override def onPull(): Unit = {
        if (hasInitialElement) {
          substreamSource.push(firstElem)
          firstElem = null.asInstanceOf[T]
          setKeepGoing(false)
          if (willCompleteAfterInitialElement) {
            substreamSource.complete()
            completeStage()
          }
        } else pull(in)
      }

      override def onDownstreamFinish(): Unit = {
        substreamCancelled = true
        if (isClosed(in) || propagateSubstreamCancel) {
          completeStage()
        } else {
          // Start draining
          if (!hasBeenPulled(in)) pull(in)
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
            else substreamSource.push(elem)
          }
        } catch {
          case NonFatal(ex) ⇒ onUpstreamFailure(ex)
        }
      }

      override def onUpstreamFinish(): Unit =
        if (hasInitialElement) willCompleteAfterInitialElement = true
        else {
          substreamSource.complete()
          completeStage()
        }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        substreamSource.fail(ex)
        failStage(ex)
      }

    }
  }
  override def toString: String = "Split"

}

/**
 * INTERNAL API
 */
object SubSink {
  sealed trait Command
  case object RequestOne extends Command
  case object Cancel extends Command
}

/**
 * INTERNAL API
 */
final class SubSink[T](name: String, externalCallback: ActorSubscriberMessage ⇒ Unit)
  extends GraphStage[SinkShape[T]] {
  import SubSink._

  private val in = Inlet[T]("SubSink.in")

  override def initialAttributes = Attributes.name(s"SubSink($name)")
  override val shape = SinkShape(in)

  private val status = new AtomicReference[AnyRef]

  def pullSubstream(): Unit = {
    status.get match {
      case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(RequestOne)
      case null ⇒
        if (!status.compareAndSet(null, RequestOne))
          status.get.asInstanceOf[Command ⇒ Unit](RequestOne)
    }
  }

  def cancelSubstream(): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(Cancel)
    case x ⇒ // a potential RequestOne is overwritten
      if (!status.compareAndSet(x, Cancel))
        status.get.asInstanceOf[Command ⇒ Unit](Cancel)
  }

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) with InHandler {
    setHandler(in, this)

    override def onPush(): Unit = externalCallback(ActorSubscriberMessage.OnNext(grab(in)))
    override def onUpstreamFinish(): Unit = externalCallback(ActorSubscriberMessage.OnComplete)
    override def onUpstreamFailure(ex: Throwable): Unit = externalCallback(ActorSubscriberMessage.OnError(ex))

    @tailrec private def setCB(cb: AsyncCallback[Command]): Unit = {
      status.get match {
        case null ⇒
          if (!status.compareAndSet(null, cb)) setCB(cb)
        case RequestOne ⇒
          pull(in)
          if (!status.compareAndSet(RequestOne, cb)) setCB(cb)
        case Cancel ⇒
          completeStage()
          if (!status.compareAndSet(Cancel, cb)) setCB(cb)
        case _: AsyncCallback[_] ⇒
          failStage(new IllegalStateException("Substream Source cannot be materialized more than once"))
      }
    }

    override def preStart(): Unit = {
      val ourOwnCallback = getAsyncCallback[Command] {
        case RequestOne ⇒ tryPull(in)
        case Cancel     ⇒ completeStage()
        case _          ⇒ throw new IllegalStateException("Bug")
      }
      setCB(ourOwnCallback)
    }
  }

  override def toString: String = name
}

object SubSource {
  /**
   * INTERNAL API
   *
   * HERE ACTUALLY ARE DRAGONS, YOU HAVE BEEN WARNED!
   *
   * FIXME #19240
   */
  private[akka] def kill[T, M](s: Source[T, M]): Unit = {
    s.module match {
      case GraphStageModule(_, _, stage: SubSource[_]) ⇒
        stage.externalCallback.invoke(SubSink.Cancel)
      case pub: PublisherSource[_] ⇒
        pub.create(null)._1.subscribe(new CancellingSubscriber)
      case m ⇒
        GraphInterpreter.currentInterpreterOrNull match {
          case null ⇒ throw new UnsupportedOperationException(s"cannot drop Source of type ${m.getClass.getName}")
          case intp ⇒ s.runWith(Sink.ignore)(intp.subFusingMaterializer)
        }
    }
  }
}

/**
 * INTERNAL API
 */
final class SubSource[T](name: String, private[fusing] val externalCallback: AsyncCallback[SubSink.Command])
  extends GraphStage[SourceShape[T]] {
  import SubSink._

  val out: Outlet[T] = Outlet("SubSource.out")
  override def initialAttributes = Attributes.name(s"SubSource($name)")
  override val shape: SourceShape[T] = SourceShape(out)

  private val status = new AtomicReference[AnyRef]

  def pushSubstream(elem: T): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(ActorSubscriberMessage.OnNext(elem))
    case _                                ⇒ throw new IllegalStateException("cannot push to uninitialized substream")
  }

  def completeSubstream(): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(ActorSubscriberMessage.OnComplete)
    case null ⇒
      if (!status.compareAndSet(null, ActorSubscriberMessage.OnComplete))
        status.get.asInstanceOf[AsyncCallback[Any]].invoke(ActorSubscriberMessage.OnComplete)
  }

  def failSubstream(ex: Throwable): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(ActorSubscriberMessage.OnError(ex))
    case null ⇒
      val failure = ActorSubscriberMessage.OnError(ex)
      if (!status.compareAndSet(null, failure))
        status.get.asInstanceOf[AsyncCallback[Any]].invoke(failure)
  }

  def timeout(d: FiniteDuration): Boolean =
    status.compareAndSet(null, ActorSubscriberMessage.OnError(new SubscriptionTimeoutException(s"Substream Source has not been materialized in $d")))

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with OutHandler {
    setHandler(out, this)

    @tailrec private def setCB(cb: AsyncCallback[ActorSubscriberMessage]): Unit = {
      status.get match {
        case null                               ⇒ if (!status.compareAndSet(null, cb)) setCB(cb)
        case ActorSubscriberMessage.OnComplete  ⇒ completeStage()
        case ActorSubscriberMessage.OnError(ex) ⇒ failStage(ex)
        case _: AsyncCallback[_]                ⇒ failStage(new IllegalStateException("Substream Source cannot be materialized more than once"))
      }
    }

    override def preStart(): Unit = {
      val ourOwnCallback = getAsyncCallback[ActorSubscriberMessage] {
        case ActorSubscriberMessage.OnComplete   ⇒ completeStage()
        case ActorSubscriberMessage.OnError(ex)  ⇒ failStage(ex)
        case ActorSubscriberMessage.OnNext(elem) ⇒ push(out, elem.asInstanceOf[T])
      }
      setCB(ourOwnCallback)
    }

    override def onPull(): Unit = externalCallback.invoke(RequestOne)
    override def onDownstreamFinish(): Unit = externalCallback.invoke(Cancel)
  }

  override def toString: String = name
}
