package akka.typed
package javadsl

import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters
import akka.util.Timeout
import akka.actor.Scheduler
import scaladsl.AskPattern._
import akka.japi.function.Function

object AskPattern {
  def ask[T, U](actor: ActorRef[T], message: Function[ActorRef[U], T], timeout: Timeout, scheduler: Scheduler): CompletionStage[U] =
    FutureConverters.toJava[U](actor.?(message.apply)(timeout, scheduler))
}