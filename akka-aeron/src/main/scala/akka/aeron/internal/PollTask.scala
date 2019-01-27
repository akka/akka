/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron.internal

import akka.stream.stage.AsyncCallback
import io.aeron.Subscription
import io.aeron.logbuffer.FragmentHandler

object PollTask {
  def pollTask[T >: Null](sub: Subscription, handler: MessageHandler[T], onMessage: AsyncCallback[T]): TaskRunner.Task = {
    () ⇒
      {
        handler.reset()
        sub.poll(handler.fragmentsHandler, 1)
        val msg = handler.messageReceived
        handler.reset() // for GC
        if (msg != null) {
          onMessage.invoke(msg)
          true
        } else
          false
      }
  }

  abstract class MessageHandler[T >: Null]() {
    def reset(): Unit = messageReceived = null

    var messageReceived: T = null
    val fragmentsHandler: FragmentHandler
  }

}
