/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.annotation.InternalApi
import akka.stream._
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Attributes.{ SourceLocation }
import akka.stream.impl.{ Buffer => BufferImpl, PartitionedBuffer }
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage._
import akka.util.OptionVal

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

/**
 * Internal API
 */
@InternalApi
private[akka] object MapAsyncPartitioned {
  def apply[In, Out, Partition](
      parallelism: Int,
      perPartition: Int,
      partitioner: In => Partition,
      f: (In, Partition) => Future[Out]): GraphStage[FlowShape[In, Out]] =
    if (parallelism < 1) throw new IllegalArgumentException("parallelism must be at least 1")
    else if (perPartition < 1) throw new IllegalArgumentException("perPartition must be at least 1")
    else if (perPartition >= parallelism) {
      // no point controlling per-partition, so just use a MapAsync, which has somewhat less overhead
      val fin = { (x: In) =>
        f(x, partitioner(x))
      }
      MapAsync(parallelism, fin)
    } else new MapAsyncPartitioned(parallelism, perPartition, partitioner, f) {}

  final class Holder[P, I, O](
      var incoming: I,
      var outgoing: Try[O],
      val partition: P,
      val cb: AsyncCallback[Holder[P, I, O]])
      extends (Try[O] => Unit) {
    private var cachedSupervisionDirective: OptionVal[Supervision.Directive] = OptionVal.None

    def supervisionDirectiveFor(decider: Supervision.Decider, ex: Throwable): Supervision.Directive =
      cachedSupervisionDirective match {
        case OptionVal.Some(d) => d
        case _ =>
          val d = decider(ex)
          cachedSupervisionDirective = OptionVal.Some(d)
          d
      }

    def clearIncoming(): Unit = {
      incoming = null.asInstanceOf[I]
    }

    def setOutgoing(t: Try[O]): Unit = {
      outgoing = t
    }

    override def apply(t: Try[O]): Unit = {
      setOutgoing(t)
      cb.invoke(this)
    }

    override def toString: String = s"Holder($incoming, $outgoing, $partition)"
  }

  val NotYetThere = MapAsync.NotYetThere
}

/**
 * Internal API
 */
@InternalApi
private[akka] sealed abstract case class MapAsyncPartitioned[In, Out, Partition] private (
    parallelism: Int,
    perPartition: Int,
    partitioner: In => Partition,
    f: (In, Partition) => Future[Out])
    extends GraphStage[FlowShape[In, Out]] {
  import MapAsyncPartitioned._

  private val in = Inlet[In]("MapAsyncPartitioned.in")
  private val out = Outlet[Out]("MapAsyncPartitioned.out")

  override def initialAttributes = DefaultAttributes.mapAsync and SourceLocation.forLambda(f)
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
      var buffer: PartitionedBuffer[Partition, Holder[Partition, In, Out]] = _

      private val futureCB = getAsyncCallback[Holder[Partition, In, Out]] { holder =>
        holder.outgoing match {
          case Failure(ex) if holder.supervisionDirectiveFor(decider, ex) == Supervision.Stop => failStage(ex)
          case _ =>
            dropCompletedThenPushIfPossible(holder.partition)
        }
      }

      override def preStart(): Unit = {
        buffer = new PartitionedBuffer[Partition, Holder[Partition, In, Out]](
          linearBuffer = BufferImpl(parallelism, inheritedAttributes),
          partitioner = _.partition,
          makePartitionBuffer = { (p, overflowCapacity) =>
            import akka.stream.impl.{ BoundedBuffer, ChainedBuffer }
            val tailCapacity = (overflowCapacity - perPartition).max(1)

            new ChainedBuffer[Holder[Partition, In, Out]](
              BufferImpl(perPartition, inheritedAttributes),
              new BoundedBuffer.DynamicQueue(tailCapacity), {
                holder =>
                  // this will execute when the holder moves from the tail buffer to the head buffer (viz. ready to be
                  //  scheduled).  This might be on enqueueing into the partitioned buffer, or it might be when
                  //  the head of this partition is dropped after being completed.
                  try {
                    val future = f(holder.incoming, p)
                    holder.clearIncoming()

                    future.value match {
                      case None    => future.onComplete(holder)(akka.dispatch.ExecutionContexts.parasitic)
                      case Some(v) =>
                        // future already completed, so don't schedule on dispatcher
                        holder.setOutgoing(v)
                        v match {
                          case Failure(ex) if holder.supervisionDirectiveFor(decider, ex) == Supervision.Stop =>
                            failStage(ex)

                          case _ =>
                            dropCompletedThenPushIfPossible(p)
                        }
                    }
                  } catch {
                    // executes if f throws, not a failed future
                    case NonFatal(ex) =>
                      val directive = holder.supervisionDirectiveFor(decider, ex)
                      if (directive == Supervision.Stop) failStage(ex)
                      else {
                        holder.clearIncoming()
                        if (holder.outgoing == NotYetThere) {
                          holder.setOutgoing(Failure(ex))
                          dropCompletedThenPushIfPossible(p)
                        } else {
                          failStage(
                            new IllegalStateException(
                              "Should only get here if f throws, not if future was created",
                              ex))
                        }
                      }
                  }
              })
          })
      }

      override def onPull(): Unit = pushNextIfPossible()

      override def onPush(): Unit = {
        try {
          val elem = grab(in)
          val holder = new Holder[Partition, In, Out](
            incoming = elem,
            outgoing = NotYetThere,
            partition = partitioner(elem),
            cb = futureCB)

          buffer.enqueue(holder)
        } catch {
          case NonFatal(ex) =>
            if (decider(ex) == Supervision.Stop) failStage(ex)
        }

        pullIfNeeded()
      }

      override def onUpstreamFinish(): Unit = if (buffer.isEmpty) completeStage()

      @tailrec
      private def dropCompletedThenPushIfPossible(partition: Partition): Unit = {
        val holderOpt = buffer.peekPartition(partition)
        holderOpt match {
          case None => pushNextIfPossible()
          case Some(holder) =>
            holder.outgoing match {
              case NotYetThere                                                                              => pushNextIfPossible()
              case Failure(NonFatal(ex)) if holder.supervisionDirectiveFor(decider, ex) == Supervision.Stop =>
                // Could happen if this finds the failed future before the async callback runs
                failStage(ex)

              case Failure(NonFatal(_)) | Success(_) =>
                buffer.dropOnlyPartitionHead(partition)
                dropCompletedThenPushIfPossible(partition)

              case Failure(ex) =>
                // fatal exception in the buffer, not sure if this can actually happen, but for completeness...
                throw ex
            }
        }
      }

      @tailrec
      private def pushNextIfPossible(): Unit =
        if (buffer.isEmpty) pullIfNeeded()
        else if (buffer.peek().outgoing eq NotYetThere) pullIfNeeded()
        else if (isAvailable(out)) {
          // We peek instead of dequeue so that we can push out an element before
          //  removing from the queue (since a dequeue or dropHead can cause re-entry)
          val holder = buffer.peek()
          holder.outgoing match {
            case Success(elem) =>
              if (elem != null) {
                push(out, elem)
                buffer.dropHead()
                pullIfNeeded()
              } else {
                // elem is null
                buffer.dropHead()
                pullIfNeeded()
                pushNextIfPossible()
              }

            case Failure(NonFatal(ex)) if holder.supervisionDirectiveFor(decider, ex) == Supervision.Stop =>
              failStage(ex)

            case Failure(NonFatal(_)) =>
              buffer.dropHead()
              pushNextIfPossible() // try the next element

            case Failure(ex) => throw ex
          }
        }

      private def pullIfNeeded(): Unit = {
        if (isClosed(in) && buffer.isEmpty) completeStage()
        else if (buffer.used < parallelism && !hasBeenPulled(in)) tryPull(in)
        // else already pulled and waiting for next element
      }

      setHandlers(in, out, this)
    }
}
