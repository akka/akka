/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.event

import akka.actor.Actor
import akka.actor.DeadLetter
import akka.event.Logging.Info

class DeadLetterListener extends Actor {

  val eventStream = context.system.eventStream
  val maxCount = context.system.settings.LogDeadLetters
  var count = 0

  override def preStart(): Unit =
    eventStream.subscribe(self, classOf[DeadLetter])

  // don't re-subscribe, skip call to preStart
  override def postRestart(reason: Throwable): Unit = ()

  // don't remove subscription, skip call to postStop, no children to stop
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = ()

  override def postStop(): Unit =
    eventStream.unsubscribe(self)

  def receive = {
    case DeadLetter(message, snd, rcp) ⇒
      count += 1
      val done = maxCount != Int.MaxValue && count >= maxCount
      val doneMsg = if (done) ", no more dead letters will be logged" else ""
      eventStream.publish(Info(rcp.path.toString, rcp.getClass,
        s"Message [${message.getClass.getName}] from $snd to $rcp was not delivered. [$count] dead letters encountered$doneMsg. " +
          "This logging can be turned off or adjusted with configuration settings 'akka.log-dead-letters' " +
          "and 'akka.log-dead-letters-during-shutdown'."))
      if (done) context.stop(self)
  }

}
