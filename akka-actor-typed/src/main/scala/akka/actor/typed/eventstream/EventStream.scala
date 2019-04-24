/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream

import akka.actor.typed.ActorRef

import scala.reflect.ClassTag

object EventStream {

  sealed trait Command
  case class Publish[E](event: E) extends Command
  case class Subscribe[E](subscriber: ActorRef[E])(implicit classTag: ClassTag[E]) extends Command {
    private[akka] def topic: Class[_] = classTag.runtimeClass
  }

  case class Unsubscribe[E](subscriber: ActorRef[E]) extends Command
}
