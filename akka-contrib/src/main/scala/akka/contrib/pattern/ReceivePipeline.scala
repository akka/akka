/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.pattern

import akka.actor.Actor

@deprecated("Feel free to copy", "2.5.0")
object ReceivePipeline {

  /**
   * Result returned by an interceptor PF to determine what/whether to delegate to the next inner interceptor
   */
  sealed trait Delegation

  case class Inner(transformedMsg: Any) extends Delegation {

    /**
     * Add a block of code to be executed after the message (which may be further transformed and processed by
     * inner interceptors) is handled by the actor's receive.
     *
     * The block of code will be executed before similar blocks in outer interceptors.
     */
    def andAfter(after: => Unit): Delegation = InnerAndAfter(transformedMsg, (_ => after))
  }

  private[ReceivePipeline] case class InnerAndAfter(transformedMsg: Any, after: Unit => Unit) extends Delegation

  /**
   * Interceptor return value that indicates that the message has been handled
   * completely. The message will not be passed to inner interceptors
   * (or to the decorated actor's receive).
   */
  case object HandledCompletely extends Delegation

  private def withDefault(interceptor: Interceptor): Interceptor = interceptor.orElse({ case msg => Inner(msg) })

  type Interceptor = PartialFunction[Any, Delegation]

  private sealed trait HandlerResult
  private case object Done extends HandlerResult
  private case object Undefined extends HandlerResult

  private type Handler = Any => HandlerResult
}

/**
 * Trait implementing Receive Pipeline Pattern. Mixin this trait
 * for configuring a chain of interceptors to be applied around
 * Actor's current behavior.
 */
@deprecated("Feel free to copy", "2.5.0")
trait ReceivePipeline extends Actor {
  import ReceivePipeline._

  private var pipeline: Vector[Interceptor] = Vector.empty
  private var decoratorCache: Option[(Receive, Receive)] = None

  /**
   * Adds an inner interceptor, it will be applied lastly, near to Actor's original behavior
   * @param interceptor an interceptor
   */
  def pipelineInner(interceptor: Interceptor): Unit = {
    pipeline :+= withDefault(interceptor)
    decoratorCache = None
  }

  /**
   * Adds an outer interceptor, it will be applied firstly, far from Actor's original behavior
   * @param interceptor an interceptor
   */
  def pipelineOuter(interceptor: Interceptor): Unit = {
    pipeline +:= withDefault(interceptor)
    decoratorCache = None
  }

  private def combinedDecorator: Receive => Receive = { receive =>
    // So that reconstructed Receive PF is undefined only when the actor's
    // receive is undefined for a transformed message that reaches it...
    val innerReceiveHandler: Handler = {
      case msg => receive.lift(msg).map(_ => Done).getOrElse(Undefined)
    }

    val zipped = pipeline.foldRight(innerReceiveHandler) { (outerInterceptor, innerHandler) =>
      outerInterceptor.andThen {
        case Inner(msg) => innerHandler(msg)
        case InnerAndAfter(msg, after) =>
          try innerHandler(msg)
          finally after(())
        case HandledCompletely => Done
      }
    }

    toReceive(zipped)
  }

  private def toReceive(handler: Handler) = new Receive {
    def isDefinedAt(m: Any): Boolean = evaluate(m) != Undefined
    def apply(m: Any): Unit = evaluate(m)

    override def applyOrElse[A1 <: Any, B1 >: Unit](m: A1, default: A1 => B1): B1 = {
      val result = handler(m)

      if (result == Undefined) default(m)
    }

    private def evaluate(m: Any) = handler(m)
  }

  /**
   * INTERNAL API.
   */
  override protected[akka] def aroundReceive(receive: Receive, msg: Any): Unit = {
    def withCachedDecoration(decorator: Receive => Receive): Receive = decoratorCache match {
      case Some((`receive`, cached)) => cached
      case _ =>
        val decorated = decorator(receive)
        decoratorCache = Some((receive, decorated))
        decorated
    }

    super.aroundReceive(withCachedDecoration(combinedDecorator), msg)
  }
}
