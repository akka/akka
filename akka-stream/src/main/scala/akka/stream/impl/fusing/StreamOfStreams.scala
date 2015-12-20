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
import akka.stream.impl.MultiStreamOutputProcessor.SubstreamSubscriptionTimeout
import scala.annotation.tailrec
import akka.stream.impl.PublisherSource
import akka.stream.impl.CancellingSubscriber
import akka.stream.impl.BoundedBuffer

/**
 * INTERNAL API
 */
final class FlattenMerge[T, M](breadth: Int) extends GraphStage[FlowShape[Graph[SourceShape[T], M], T]] {
  private val in = Inlet[Graph[SourceShape[T], M]]("flatten.in")
  private val out = Outlet[T]("flatten.out")

  override def initialAttributes = Attributes.name("FlattenMerge")
  override val shape = FlowShape(in, out)

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {

    var sources = Set.empty[SubSinkInlet[T]]
    def activeSources = sources.size

    val q = new BoundedBuffer[SubSinkInlet[T]](breadth)

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
        override def onUpstreamFinish(): Unit = {
          if (!sinkIn.isAvailable) removeSource(sinkIn)
        }
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

    override def postStop(): Unit = {
      sources.foreach(_.cancel())
    }
  }

  override def toString: String = s"FlattenMerge($breadth)"
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

    private var left = if (n < 0) 0 else n
    private var builder = Vector.newBuilder[T]
    builder.sizeHint(left)

    private var tailSource: SubSourceOutlet[T] = null

    private val SubscriptionTimer = "SubstreamSubscriptionTimer"

    override protected def onTimer(timerKey: Any): Unit = {
      val timeout = ActorMaterializer.downcast(interpreter.materializer).settings.subscriptionTimeoutSettings.timeout
      tailSource.timeout(timeout)
      if (tailSource.isClosed) completeStage()
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

    private def openSubstream(): Source[T, Unit] = {
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
    import Split._

    private val SubscriptionTimer = "SubstreamSubscriptionTimer"

    private var timeout: FiniteDuration = _
    private var substreamSource: SubSourceOutlet[T] = null
    private var substreamPushed = false
    private var substreamCancelled = false

    override def preStart(): Unit = {
      timeout = ActorMaterializer.downcast(interpreter.materializer).settings.subscriptionTimeoutSettings.timeout
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (substreamSource eq null) pull(in)
        else if (!substreamPushed) {
          push(out, Source.fromGraph(substreamSource.source))
          scheduleOnce(SubscriptionTimer, timeout)
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
        substreamSource = new SubSourceOutlet[T]("SplitSource")
        substreamSource.setHandler(handler)
        substreamCancelled = false
        setHandler(in, handler)
        setKeepGoing(enabled = handler.hasInitialElement)

        if (isAvailable(out)) {
          push(out, Source.fromGraph(substreamSource.source))
          scheduleOnce(SubscriptionTimer, timeout)
          substreamPushed = true
        } else substreamPushed = false
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
        if (isClosed(in)) completeStage()
        else {
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
}

/**
 * INTERNAL API
 */
object SubSink {
  val RequestOne = Request(1) // No need to frivolously allocate these
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

  val status = new AtomicReference[AnyRef]

  def pullSubstream(): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(RequestOne)
    case null ⇒
      if (!status.compareAndSet(null, RequestOne))
        status.get.asInstanceOf[ActorPublisherMessage ⇒ Unit](RequestOne)
  }

  def cancelSubstream(): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(Cancel)
    case x ⇒ // a potential RequestOne is overwritten
      if (!status.compareAndSet(x, Cancel))
        status.get.asInstanceOf[ActorPublisherMessage ⇒ Unit](Cancel)
  }

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) with InHandler {
    setHandler(in, this)

    override def onPush(): Unit = externalCallback(OnNext(grab(in)))
    override def onUpstreamFinish(): Unit = externalCallback(OnComplete)
    override def onUpstreamFailure(ex: Throwable): Unit = externalCallback(OnError(ex))

    @tailrec private def setCB(cb: AsyncCallback[ActorPublisherMessage]): Unit = {
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
      val ourOwnCallback = getAsyncCallback[ActorPublisherMessage] {
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
        stage.externalCallback.invoke(Cancel)
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
final class SubSource[T](name: String, private[fusing] val externalCallback: AsyncCallback[ActorPublisherMessage])
  extends GraphStage[SourceShape[T]] {
  import SubSink._

  val out: Outlet[T] = Outlet("SubSource.out")
  override def initialAttributes = Attributes.name(s"SubSource($name)")
  override val shape: SourceShape[T] = SourceShape(out)

  val status = new AtomicReference[AnyRef]

  def pushSubstream(elem: T): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(OnNext(elem))
    case _                                ⇒ throw new IllegalStateException("cannot push to uninitialized substream")
  }

  def completeSubstream(): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(OnComplete)
    case null ⇒
      if (!status.compareAndSet(null, OnComplete))
        status.get.asInstanceOf[AsyncCallback[Any]].invoke(OnComplete)
  }

  def failSubstream(ex: Throwable): Unit = status.get match {
    case f: AsyncCallback[Any] @unchecked ⇒ f.invoke(OnError(ex))
    case null ⇒
      val failure = OnError(ex)
      if (!status.compareAndSet(null, failure))
        status.get.asInstanceOf[AsyncCallback[Any]].invoke(failure)
  }

  def timeout(d: FiniteDuration): Boolean =
    status.compareAndSet(null, OnError(new SubscriptionTimeoutException(s"Substream Source has not been materialized in $d")))

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with OutHandler {
    setHandler(out, this)

    @tailrec private def setCB(cb: AsyncCallback[ActorSubscriberMessage]): Unit = {
      status.get match {
        case null                ⇒ if (!status.compareAndSet(null, cb)) setCB(cb)
        case OnComplete          ⇒ completeStage()
        case OnError(ex)         ⇒ failStage(ex)
        case _: AsyncCallback[_] ⇒ failStage(new IllegalStateException("Substream Source cannot be materialized more than once"))
      }
    }

    override def preStart(): Unit = {
      val ourOwnCallback = getAsyncCallback[ActorSubscriberMessage] {
        case OnComplete   ⇒ completeStage()
        case OnError(ex)  ⇒ failStage(ex)
        case OnNext(elem) ⇒ push(out, elem.asInstanceOf[T])
      }
      setCB(ourOwnCallback)
    }

    override def onPull(): Unit = externalCallback.invoke(RequestOne)
    override def onDownstreamFinish(): Unit = externalCallback.invoke(Cancel)
  }

  override def toString: String = name
}
