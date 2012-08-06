package akka.actor

import akka.dispatch.SystemMessage
import java.util.concurrent.atomic.AtomicLong
import collection.mutable.Queue
import com.typesafe.config.{ Config, ConfigFactory }
import akka.event.LoggingAdapter
import scala.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * The ActorDSL object provides factory methods which allow creating actors
 * using a very lightweight syntax which simplifies programming with actors
 * in the interactive interpreter, for example.
 *
 * Moreover, the ActorDSL object provides an actor interface for the current
 * thread. By importing the `self` member, the current thread is treated
 * as an implicit sender when sending messages outside of a regular actor.
 * Using the `receive` method, the current thread can receive messages
 * sent to its actor reference.
 *
 * NOTE: The primary purpose of this object is to provide concise lightweight syntax for
 * experimentation, prototyping and very low throughput code. Actors created with this object
 * should not be exposed to high message throughput and concurrency.
 */
object ActorDSL {
  private val system = new DSLActorSystem
  system.start

  /* The ActorRef of the current thread with its message queue */
  private val tl = new ThreadLocal[(ActorRef, Queue[(Any, ActorRef)])]
  private val selfTl = new ThreadLocal[ActorRef]
  private val senderTl = new ThreadLocal[ActorRef]

  /**
   * Factory method for creating and starting an actor.
   *
   * @param  creator  The code block that creates the new actor
   * @return          The newly created actor. Note that it is automatically started.
   */
  def actorOf(body: ActorContext ⇒ PartialFunction[Any, Unit]): ActorRef =
    system actorOf Props(new Actor {
      val receiveBody = new PartialFunction[Any, Unit] {
        val bodyPf = body(context)
        def isDefinedAt(v: Any) = {
          // store the sender for use in body
          senderTl.set(context.sender)
          selfTl.set(context.self)
          val res = bodyPf.isDefinedAt(v)
          senderTl.set(null)
          selfTl.set(null)
          res
        }

        def apply(v: Any) = {
          // store the sender for use in body
          senderTl.set(context.sender)
          selfTl.set(context.self)
          val res = bodyPf(v)
          senderTl.set(null)
          selfTl.set(null)
          res
        }
      }

      def receive: Receive = receiveBody
    })

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
  def actorOf[T <: Actor](creator: ⇒ T): ActorRef =
    if (creator.isInstanceOf[Stash])
      system actorOf Props(creator = () ⇒ creator, dispatcher = "akka.actor.default-stash-dispatcher")
    else
      system actorOf Props(creator)

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
  def receive[T](pf: PartialFunction[Any, T]): T = {
    val queue = tl.get._2
    var done = false // guarded by queue
    var msgOpt: Option[(Any, ActorRef)] = None // guarded by queue
    val prevSender: ActorRef = senderTl.get

    // wait for the message to arrive
    while (!done) {
      queue.synchronized {
        // find (and remove) first message that matches any of the patterns in pf
        msgOpt = queue.dequeueFirst(m ⇒ pf.isDefinedAt(m._1))
        if (msgOpt.isEmpty) queue.wait()
        else done = true
      }
    }

    // apply partial function to message and keep the sender info
    senderTl.set(msgOpt.get._2)
    val res = pf(msgOpt.get._1)
    senderTl.set(prevSender)

    res
  }

  def sender: ActorRef = senderTl.get

  /**
   * The actor reference of the current thread or anonymous actor create .
   */
  implicit def self: ActorRef = if (selfTl.get != null)
    // if this is an Akka actor return the context.sender
    selfTl.get
  else
    // return thread actor
    threadActor

  private def threadActor: ActorRef = {
    val s = tl.get
    if (s ne null) s._1
    else {
      // initialize thread-local message queue
      val queue = Queue[(Any, ActorRef)]()
      // initialize thread-local ActorRef
      val ref = system.provider.createThreadActor(queue)
      tl set (ref, queue)

      ref
    }
  }
}

/**
 * Internal implementation detail used for threads. The container checks
 * the existence of each thread, prior to invoking any CRUD operation. This ensures
 * that the ThreadActor state observed will be consistent.
 *
 * NOTE: This class imposes significant performance penalty for each operation and should
 * not be used for other use cases.
 */
private[akka] class ThreadPathContainer(
  override val provider: ActorRefProvider,
  override val path: ActorPath,
  override val getParent: InternalActorRef,
  val log: LoggingAdapter) extends MinimalActorRef {

  private[this] val children = new ConcurrentHashMap[String, WeakReference[ThreadActorRef]]()

  import scala.collection.JavaConversions._
  def clearHangingThreadActors =
    children.filter(_._2.get match {
      case None ⇒ true
      case Some(ref) ⇒ {
        ref.thread.getState == Thread.State.TERMINATED
      }
    }).map(_._1).foreach(children.remove(_))

  def addChild(name: String, ref: ThreadActorRef): Unit = {
    clearHangingThreadActors
    children.put(name, new WeakReference(ref)) match {
      case null ⇒ // okay
      case old  ⇒ log.warning("{} replacing child {} ({} -> {})", path, name, old, ref)
    }
  }

  def removeChild(name: String): Unit = {
    clearHangingThreadActors
    if (children.remove(name) eq null)
      log.warning("{} trying to remove non-child {}", path, name)
  }

  def getChild(name: String): ActorRef = {
    clearHangingThreadActors
    getChildInternal(name)
  }

  private def getChildInternal(name: String) =
    children.get(name) match {
      case null ⇒ null
      case x ⇒ x.get match {
        case None      ⇒ null
        case Some(ref) ⇒ ref
      }
    }

  override def getChild(name: Iterator[String]): InternalActorRef = {
    clearHangingThreadActors
    if (name.isEmpty) this
    else {
      val n = name.next()
      if (n.isEmpty) this
      else getChildInternal(n) match {
        case null ⇒ Nobody
        case some ⇒
          if (name.isEmpty) some
          else some.getChild(name)
      }
    }
  }

}

private class DSLActorSystem extends ActorSystemImpl("DSLActorSystem",
  ConfigFactory.load().getConfig("akka.actor.dsl-actor-system"),
  Thread.currentThread().getContextClassLoader())

private class ThreadActorRef(
  val provider: ActorRefProvider,
  val path: ActorPath,
  val queue: Queue[(Any, ActorRef)],
  var thread: Thread) extends MinimalActorRef {
  override def !(message: Any)(implicit sender: ActorRef = null): Unit = {
    /* put message into queue and notify receiving thread
       note that we are ignoring the sender, because the current thread
       does not have a context anyway
    */
    queue.synchronized {
      queue += ((message, sender))
      // notify the thread that's waiting
      queue.notifyAll()
    }
  }
}
