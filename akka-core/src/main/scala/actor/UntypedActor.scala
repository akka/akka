/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

/**
 * FIXME: document 
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class UntypedActor extends Actor {
  protected[akka] var context: Option[ActorContext] = None

  protected def receive = {
    case msg => 
      if (context.isEmpty) {
        val ctx = new ActorContext(self)
        context = Some(ctx)
        onReceive(msg, ctx)
      } else onReceive(msg, context.get)
  }

  def onReceive(message: Any, context: ActorContext): Unit
}

/**
 * FIXME: document 
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActorContext(val self: ActorRef) {
}