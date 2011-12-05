/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.japi.{ Creator, Procedure }
import akka.dispatch.{ MessageDispatcher, Promise }

/**
 * Subclass this abstract class to create a MDB-style untyped actor.
 * <p/>
 * This class is meant to be used from Java.
 * <p/>
 * Here is an example on how to create and use an UntypedActor:
 * <pre>
 *  public class SampleUntypedActor extends UntypedActor {
 *    public void onReceive(Object message) throws Exception {
 *      if (message instanceof String) {
 *        String msg = (String)message;
 *
 *        if (msg.equals("UseReply")) {
 *          // Reply to original sender of message using the 'reply' method
 *          getContext().getSender().tell(msg + ":" + getSelf().getAddress());
 *
 *        } else if (msg.equals("UseSender") && getSender().isDefined()) {
 *          // Reply to original sender of message using the sender reference
 *          // also passing along my own reference (the self)
 *          getSender().get().tell(msg, getSelf());
 *
 *        } else if (msg.equals("SendToSelf")) {
 *          // Send message to the actor itself recursively
 *          getSelf().tell(msg)
 *
 *        } else if (msg.equals("ForwardMessage")) {
 *          // Retreive an actor from the ActorRegistry by ID and get an ActorRef back
 *          ActorRef actorRef = Actor.registry.local.actorsFor("some-actor-id").head();
 *
 *        } else throw new IllegalArgumentException("Unknown message: " + message);
 *      } else throw new IllegalArgumentException("Unknown message: " + message);
 *    }
 *
 *    public static void main(String[] args) {
 *      ActorRef actor = Actors.actorOf(SampleUntypedActor.class);
 *      actor.tell("SendToSelf");
 *      actor.stop();
 *    }
 *  }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class UntypedActor extends Actor {

  /**
   * To be implemented by concrete UntypedActor. Defines the message handler.
   */
  @throws(classOf[Exception])
  def onReceive(message: Any): Unit

  def getContext(): JavaActorContext = context.asInstanceOf[JavaActorContext]

  /**
   * Returns the 'self' reference.
   */
  def getSelf(): ActorRef = self

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def getSender(): ActorRef = sender

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started, this only happens at most once in the life of an actor.
   */
  override def preStart() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop()' is invoked.
   */
  override def postStop() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called on a crashed Actor right BEFORE it is restarted to allow clean up of resources before Actor is terminated.
   */
  override def preRestart(reason: Throwable, lastMessage: Option[Any]) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
   */
  override def postRestart(reason: Throwable) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when a message isn't handled by the current behavior of the actor
   * by default it throws an UnhandledMessageException
   */
  override def unhandled(msg: Any) {
    throw new UnhandledMessageException(msg, self)
  }

  final protected def receive = {
    case msg ⇒ onReceive(msg)
  }
}

/**
 * Factory closure for an UntypedActor, to be used with 'Actors.actorOf(factory)'.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait UntypedActorFactory extends Creator[Actor]
