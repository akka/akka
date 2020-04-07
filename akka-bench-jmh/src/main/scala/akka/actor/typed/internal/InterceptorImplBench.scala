/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import java.util.concurrent.TimeUnit

import akka.actor.typed
import akka.actor.typed.BehaviorInterceptor.ReceiveTarget
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, BehaviorInterceptor, PreRestart, TypedActorContext }
import org.openjdk.jmh.annotations._

object InterceptorImplBench {
  private val emptyBehaviorInterceptor: BehaviorInterceptor[Any, Any] = new BehaviorInterceptor[Any, Any] {
    override def aroundReceive(
        ctx: TypedActorContext[Any],
        msg: Any,
        target: BehaviorInterceptor.ReceiveTarget[Any]): Behavior[Any] = {
      Behaviors.same
    }
  }

  private val receiveTarget: ReceiveTarget[Any] = new ReceiveTarget[Any] {
    override def apply(ctx: TypedActorContext[_], msg: Any): Behavior[Any] =
      Behavior.interpretMessage(Behaviors.same, ctx.asInstanceOf[TypedActorContext[Any]], msg)

    override def signalRestart(ctx: TypedActorContext[_]): Unit =
      Behavior.interpretSignal(Behaviors.same, ctx.asInstanceOf[TypedActorContext[Any]], PreRestart)

    override def toString: String = s"ReceiveTarget($emptyBehaviorInterceptor)"
  }

  private def receiveOpt[O](ctx: typed.TypedActorContext[O], msg: O): Behavior[O] = {
    val interceptMessageClass = emptyBehaviorInterceptor.interceptMessageClass
    val result =
      if ((interceptMessageClass ne null) &&
          (interceptMessageClass == classOf[java.lang.Object] ||
          interceptMessageClass.isAssignableFrom(msg.getClass)))
        emptyBehaviorInterceptor.aroundReceive(null, msg, null)
      else
        receiveTarget.apply(ctx, msg.asInstanceOf[Any])
    result.unsafeCast
  }

  private def receive[O](ctx: typed.TypedActorContext[O], msg: O): Behavior[O] = {
    val interceptMessageClass = emptyBehaviorInterceptor.interceptMessageClass
    val result =
      if ((interceptMessageClass ne null) && interceptMessageClass.isAssignableFrom(msg.getClass))
        emptyBehaviorInterceptor.aroundReceive(null, msg, null)
      else
        receiveTarget.apply(ctx, msg.asInstanceOf[Any])
    result.unsafeCast
  }
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 15, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class InterceptorImplBench {
  @Benchmark
  def benchReceiveOpt(): Unit = {
    InterceptorImplBench.receiveOpt(null, "Hi Akka")
  }

  @Benchmark
  def benchReceiveWithoutOpt(): Unit = {
    InterceptorImplBench.receive(null, "Hi Akka")
  }
}
