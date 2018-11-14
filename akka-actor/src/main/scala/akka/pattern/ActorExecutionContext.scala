/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor.Actor
import akka.actor.ActorCell
import akka.actor.NoSerializationVerificationNeeded
import akka.dispatch.Envelope
import akka.pattern.ActorExecutionContext.ExecuteThunk

import scala.concurrent.ExecutionContext
import scala.util.Try

trait ActorExecutionContext extends Actor {
  // called `dispatcher` explicitly to clash with a potential `import context.dispatcher`
  implicit def dispatcher: ExecutionContext =
    new ExecutionContext {
      val _originalEnvelope = currentMessage
      override def execute(runnable: Runnable): Unit =
        self ! ExecuteThunk(_originalEnvelope, runnable)

      override def reportFailure(cause: Throwable): Unit = ???
    }

  abstract override protected[akka] def aroundReceive(receive: Receive, msg: Any): Unit =
    msg match {
      case ExecuteThunk(envelope, runnable) ⇒
        val oldMsg = currentMessage
        setCurrentMessage(envelope)
        Try(runnable.run()) // FIXME: what to do if thunk fails?
        setCurrentMessage(oldMsg)

      case x ⇒ super.aroundReceive(receive, msg)
    }

  private def currentMessage: Envelope = context.asInstanceOf[ActorCell].currentMessage
  private def setCurrentMessage(newMessage: Envelope): Unit = context.asInstanceOf[ActorCell].currentMessage = newMessage
}

private[akka] object ActorExecutionContext {
  final case class ExecuteThunk(originalEnvelope: Envelope, runnable: Runnable) extends NoSerializationVerificationNeeded
}
