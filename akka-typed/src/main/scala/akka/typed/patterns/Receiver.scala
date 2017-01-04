/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.patterns

import scala.concurrent.duration._
import akka.typed.ActorRef
import scala.collection.immutable
import akka.typed.Behavior
import scala.concurrent.duration.Deadline
import akka.typed.ActorContext
import java.util.LinkedList
import scala.collection.JavaConverters._
import scala.collection.immutable.Queue

// FIXME make this nice again once the Actor Algebra is implemented
object Receiver {
  import akka.typed.ScalaDSL._

  sealed trait InternalCommand[T]
  case class ReceiveTimeout[T]() extends InternalCommand[T]

  sealed trait Command[T] extends InternalCommand[T]

  /**
   * Retrieve one message from the Receiver, waiting at most for the given duration.
   */
  final case class GetOne[T](timeout: FiniteDuration)(val replyTo: ActorRef[GetOneResult[T]]) extends Command[T]
  /**
   * Retrieve all messages from the Receiver that it has queued after the given
   * duration has elapsed.
   */
  final case class GetAll[T](timeout: FiniteDuration)(val replyTo: ActorRef[GetAllResult[T]]) extends Command[T]
  /**
   * Retrieve the external address of this Receiver (i.e. the side at which it
   * takes in the messages of type T.
   */
  final case class ExternalAddress[T](replyTo: ActorRef[ActorRef[T]]) extends Command[T]

  sealed trait Replies[T]
  final case class GetOneResult[T](receiver: ActorRef[Command[T]], msg: Option[T]) extends Replies[T]
  final case class GetAllResult[T](receiver: ActorRef[Command[T]], msgs: immutable.Seq[T]) extends Replies[T]

  private final case class Enqueue[T](msg: T) extends Command[T]

  def behavior[T]: Behavior[Command[T]] =
    ContextAware[Any] { ctx ⇒
      SynchronousSelf { syncself ⇒
        Or(
          empty(ctx).widen { case c: InternalCommand[t] ⇒ c.asInstanceOf[InternalCommand[T]] },
          Static[Any] {
            case msg ⇒ syncself ! Enqueue(msg)
          })
      }
    }.narrow

  private def empty[T](ctx: ActorContext[Any]): Behavior[InternalCommand[T]] =
    Total {
      case ExternalAddress(replyTo)            ⇒ { replyTo ! ctx.self; Same }
      case g @ GetOne(d) if d <= Duration.Zero ⇒ { g.replyTo ! GetOneResult(ctx.self, None); Same }
      case g @ GetOne(d)                       ⇒ asked(ctx, Queue(Asked(g.replyTo, Deadline.now + d)))
      case g @ GetAll(d) if d <= Duration.Zero ⇒ { g.replyTo ! GetAllResult(ctx.self, Nil); Same }
      case g @ GetAll(d)                       ⇒ { ctx.schedule(d, ctx.self, GetAll(Duration.Zero)(g.replyTo)); Same }
      case Enqueue(msg)                        ⇒ queued(ctx, msg)
    }

  private def queued[T](ctx: ActorContext[Any], t: T): Behavior[InternalCommand[T]] = {
    val queue = new LinkedList[T]
    queue.add(t)
    Total {
      case ExternalAddress(replyTo) ⇒
        replyTo ! ctx.self
        Same
      case g: GetOne[t] ⇒
        g.replyTo ! GetOneResult(ctx.self, Some(queue.remove()))
        if (queue.isEmpty) empty(ctx) else Same
      case g @ GetAll(d) if d <= Duration.Zero ⇒
        g.replyTo ! GetAllResult(ctx.self, queue.iterator.asScala.toVector)
        empty(ctx)
      case g @ GetAll(d) ⇒
        ctx.schedule(d, ctx.self, GetAll(Duration.Zero)(g.replyTo))
        Same
      case Enqueue(msg) ⇒
        queue.add(msg)
        Same
    }
  }

  private case class Asked[T](replyTo: ActorRef[GetOneResult[T]], deadline: Deadline)
  private def asked[T](ctx: ActorContext[Any], queue: Queue[Asked[T]]): Behavior[InternalCommand[T]] = {
    ctx.setReceiveTimeout(queue.map(_.deadline).min.timeLeft, ReceiveTimeout())

    Total {
      case ReceiveTimeout() ⇒
        val (overdue, remaining) = queue partition (_.deadline.isOverdue)
        overdue foreach (a ⇒ a.replyTo ! GetOneResult(ctx.self, None))
        if (remaining.isEmpty) {
          ctx.cancelReceiveTimeout()
          empty(ctx)
        } else asked(ctx, remaining)
      case ExternalAddress(replyTo) ⇒ { replyTo ! ctx.self; Same }
      case g @ GetOne(d) if d <= Duration.Zero ⇒
        g.replyTo ! GetOneResult(ctx.self, None)
        asked(ctx, queue)
      case g @ GetOne(d) ⇒
        asked(ctx, queue enqueue Asked(g.replyTo, Deadline.now + d))
      case g @ GetAll(d) if d <= Duration.Zero ⇒
        g.replyTo ! GetAllResult(ctx.self, Nil)
        asked(ctx, queue)
      case g @ GetAll(d) ⇒
        ctx.schedule(d, ctx.self, GetAll(Duration.Zero)(g.replyTo))
        asked(ctx, queue)
      case Enqueue(msg) ⇒
        val (ask, q) = queue.dequeue
        ask.replyTo ! GetOneResult(ctx.self, Some(msg))
        if (q.isEmpty) {
          ctx.cancelReceiveTimeout()
          empty(ctx)
        } else asked(ctx, q)
    }
  }
}
