package akka.actor

import akka.dispatch.SystemMessage
import collection.mutable.Queue

/**
 * The ActorDSL trait provides factory methods which allow creating actors
 * using a very lightweight syntax which simplifies programming with actors
 * in the interactive interpreter, for example.
 *
 * Moreover, the ActorDSL trait provides an actor interface for the current
 * thread. By importing the `self` member, the current thread is treated
 * as an implicit sender when sending messages outside of a regular actor.
 * Using the `receive` method, the current thread can receive messages
 * sent to its actor reference.
 *
 * @tparam A  The type of the actor trait
 * @tparam AR The type of the actor reference returned by factory methods
 * @tparam CT The type of the internal actor context
 */
trait ActorDSL[A, AR, CT] {

  /**
   * Factory method for creating and starting an actor.
   *
   * @param  creator  The code block that creates the new actor
   * @return          The newly created actor. Note that it is automatically started.
   */
  def actorOf[T <: A](creator: ⇒ T): AR

  /**
   * Factory method for creating and starting an actor that loops trying to receive
   * messages using a provided partial function. Note that the actor's behavior can be
   * changed after the first message has been received.
   *
   * The parameter (of type `CT`) of the argument function (`body`) is used to provide
   * access to the internal functionality of the actor (e.g., the underlying actor
   * instance or the actor's context).
   *
   * @param  body  The function which returns the top-level message handler
   * @return       The newly created actor. Note that it is automatically started.
   */
  def actorOf(body: CT ⇒ PartialFunction[Any, Unit]): AR

  /**
   * Receives a message from the mailbox of the current thread. Calling this method
   * will block the current thread, until a matching message is received.
   *
   * @example {{{
   * receive {
   *   case "exit" => println("exiting")
   *   case 42 => println("got the answer")
   *   case x: Int => println("got an answer")
   * }
   * }}}
   *
   * @param  pf A partial function specifying patterns and actions
   * @return    The result of processing the received message
   */
  def receive[T](f: PartialFunction[Any, T]): T

  /**
   * The actor reference of the current thread.
   */
  def self: AR

}

object ActorDSL extends ActorDSL[Actor, ActorRef, ActorContext] {

  private val system = ActorSystem("ActorDSLSystem")

  /* The ActorRef of the current thread */
  private val tl = new ThreadLocal[ActorRef]
  /* The message queue of the current thread */
  private val tlQueue = new ThreadLocal[Queue[Any]]

  def actorOf(body: ActorContext ⇒ PartialFunction[Any, Unit]): ActorRef =
    system actorOf Props(new Actor {
      def receive: Receive = body(context)
    })

  def actorOf[T <: Actor](creator: ⇒ T): ActorRef =
    if (creator.isInstanceOf[Stash])
      system actorOf Props(creator = () ⇒ creator, dispatcher = "akka.actor.default-stash-dispatcher")
    else
      system actorOf Props(creator)

  def receive[T](pf: PartialFunction[Any, T]): T = {
    val queue = tlQueue.get
    var done = false // guarded by queue
    var msgOpt: Option[Any] = None // guarded by queue

    while (!done) {
      queue.synchronized {
        // find (and remove) first message that matches any of the patterns in pf
        msgOpt = queue.dequeueFirst(m ⇒ pf.isDefinedAt(m))

        if (msgOpt.isEmpty) queue.wait()
        else done = true
      }
    }

    // apply partial function to message
    pf(msgOpt.get)
  }

  implicit def self: ActorRef = {
    val s = tl.get
    if (s eq null) {
      // initialize thread-local message queue
      val queue = Queue[Any]()
      tlQueue set queue

      // initialize thread-local ActorRef
      val ref = new ThreadActorRef(queue, system)
      tl set ref

      ref
    } else
      s
  }

  private class ThreadActorRef(queue: Queue[Any], system: ActorSystem) extends MinimalActorRef {
    def provider: ActorRefProvider = null

    override def !(message: Any)(implicit sender: ActorRef = null): Unit = {
      /* put message into queue and notify receiving thread
         
         note that we are ignoring the sender, because the current thread
         does not have a context anyway
       */
      queue.synchronized {
        queue += message
        // notify the thread that's waiting
        queue.notifyAll()
      }
    }

    def path: ActorPath = null
  }

}
