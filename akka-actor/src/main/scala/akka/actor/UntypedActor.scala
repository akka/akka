/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.japi.{ Creator, Procedure }
import akka.dispatch.{ MessageDispatcher, Promise }
import java.util.{ Collection ⇒ JCollection }

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
 *          reply(msg + ":" + getSelf().getAddress());
 *
 *        } else if (msg.equals("UseSender") && getSender().isDefined()) {
 *          // Reply to original sender of message using the sender reference
 *          // also passing along my own reference (the self)
 *          getSender().get().tell(msg, getSelf());
 *
 *        } else if (msg.equals("UseSenderFuture") && getSenderFuture().isDefined()) {
 *          // Reply to original sender of message using the sender future reference
 *          getSenderFuture().get().completeWithResult(msg);
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

  /**
   * Returns the 'self' reference.
   */
  def getSelf(): ActorRef = self

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def getSender: Option[ActorRef] = sender

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '?'/'ask', else None.
   */
  def getSenderFuture: Option[Promise[Any]] = senderFuture

  /**
   * Abstraction for unification of sender and senderFuture for later reply
   */
  def getChannel: UntypedChannel = channel

  /**
   * Gets the current receive timeout
   * When specified, the receive method should be able to handle a 'ReceiveTimeout' message.
   */
  def getReceiveTimeout: Option[Long] = receiveTimeout

  /**
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  def setReceiveTimeout(timeout: Long): Unit = receiveTimeout = Some(timeout)

  /**
   * Returns an unmodifiable Java Collection containing the linked actors,
   * please note that the backing map is thread-safe but not immutable
   */
  def getLinkedActors: JCollection[ActorRef] = linkedActors

  /**
   * Returns the dispatcher (MessageDispatcher) that is used for this Actor
   */
  def getDispatcher(): MessageDispatcher = dispatcher

  /**
   * Java API for become
   */
  def become(behavior: Procedure[Any]): Unit = become(behavior, false)

  /*
   * Java API for become with optional discardOld
   */
  def become(behavior: Procedure[Any], discardOld: Boolean): Unit =
    super.become({ case msg ⇒ behavior.apply(msg) }, discardOld)

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
