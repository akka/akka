/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.actor.typed.internal.ActorRefImpl

package object typed {
  /*
   * These are safe due to the self-type of ActorRef
   */
  private[akka] implicit class ToImpl[U](val ref: ActorRef[U]) extends AnyVal {
    def sorry: ActorRefImpl[U] = ref.asInstanceOf[ActorRefImpl[U]]
  }
  // This one is necessary because Scala refuses to infer Nothing
  private[akka] implicit class ToImplNothing(val ref: ActorRef[Nothing]) extends AnyVal {
    def sorryForNothing: ActorRefImpl[Nothing] = ref.asInstanceOf[ActorRefImpl[Nothing]]
  }
}
