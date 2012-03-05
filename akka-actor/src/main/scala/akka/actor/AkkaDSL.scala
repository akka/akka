package akka.actor

import akka.util.{ Duration, Timeout }
import akka.util.duration._
import akka.dispatch.Await

trait ActorDSL[A, AR, CT] {

  /**
   * Convenience method for creating actors based on their definition.
   */
  def actorOf[T <: A](creator: T): AR

  /**
   * Convenience method for creating actors. The internal functionality of the actor is passed through the type parameter IT.
   */
  def actorOf(body: CT ⇒ PartialFunction[Any, Unit]): AR

  /**
   * Convenience method used for reacting on messages from a Thread.
   *
   * This call is blocking and the partial function is not exhaustive.
   * This method does not provide actors internal state within the partial function nor it has exceptionally good performance.
   *
   *  @pf partial function applied to received messages.
   */
  def receive(pf: PartialFunction[Any, Unit]): Unit

}

/**
 * Private version of PartialFunction that ensures that it will not interact with messages passed to the ThreadLocal Actor.
 */
private final case class BehaviorPartialFunction(val pf: PartialFunction[Any, Unit])

object AkkaDSL extends ActorDSL[Actor, ActorRef, ActorContext] {

  def actorOf(body: ActorContext ⇒ PartialFunction[Any, Unit]): ActorRef =
    system.actorOf(Props(new Actor {
      val behavior = body(context)

      def receive: Receive = new PartialFunction[Any, Unit] {
        def isDefinedAt(v: Any) = behavior.isDefinedAt(v)
        def apply(v: Any) = behavior.apply(v)
      }
    }))

  def actorOf[T <: Actor](creator: T): ActorRef =
    if (creator.isInstanceOf[Stash])
      system.actorOf(Props(creator = () ⇒ creator, dispatcher = "akka.actor.default-stash-dispatcher"))
    else
      system.actorOf(Props(creator))

  // TODO decide on the strategy for timeout
  def receive(pf: PartialFunction[Any, Unit]): Unit = {
    import akka.pattern._
    val future = threadSender.?(BehaviorPartialFunction(pf))(Timeout(1 second))
    Await.result(future, 1 second)
  }

  private lazy val system = ActorSystem("DefaultSystem")

  private lazy val tl: ThreadLocal[ActorRef] = new ThreadLocal

  implicit def threadSender: ActorRef = {
    val s = tl.get
    if (s eq null) {
      val proxy = ActorSystem("ThreadActorSystem").actorOf(Props(() ⇒ new ThreadActor, "akka.actor.default-stash-dispatcher"))
      tl.set(proxy)
      proxy
    } else
      s
  }

  /**
   * Actor that operates as the implict sender actor. By using Actors DSL it allows us to wait for the message in the regular thread.
   *
   * It first receives the behavior with BehaviorPartialFunction message and then it acts upon the behavior for a single matching message.
   * After processing the message it again accepts new behavior and returns a success code to the thread.
   */
  class ThreadActor extends Actor with Stash {

    override def receive = {
      case BehaviorPartialFunction(behavior) ⇒
        unstashAll()

        // behavior with plumbing
        val pf = new PartialFunction[Any, Unit] {

          val sndr = context.sender

          def apply(v: Any): Unit = {
            if (behavior.isDefinedAt(v)) {
              unstashAll()

              // execute receive body
              behavior(v)

              // return to original behavior
              context.unbecome

              // returns a response to the thread waiting on it
              sndr.!(0)(self)
            } else {
              stash()
            }
          }

          def isDefinedAt(v: Any) = true
        }

        // become the new behavior
        context.become(pf)
      case _ ⇒ stash()
    }
  }
}
