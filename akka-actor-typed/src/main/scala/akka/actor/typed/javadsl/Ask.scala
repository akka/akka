package akka.actor.typed
package javadsl

import java.util.concurrent.CompletionStage

import akka.actor.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.japi.function.Function
import akka.util.Timeout

import scala.compat.java8.FutureConverters

object AskPattern {
  def ask[T, U](actor: ActorRef[T], message: Function[ActorRef[U], T], timeout: Timeout, scheduler: Scheduler): CompletionStage[U] =
    FutureConverters.toJava[U](actor.?(message.apply)(timeout, scheduler))
}
