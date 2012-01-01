/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

package object actor {
  implicit def actorRef2Scala(ref: ActorRef): ScalaActorRef = ref.asInstanceOf[ScalaActorRef]
  implicit def scala2ActorRef(ref: ScalaActorRef): ActorRef = ref.asInstanceOf[ActorRef]

  type Uuid = com.eaio.uuid.UUID

  def newUuid(): Uuid = new Uuid()

  def uuidFrom(time: Long, clockSeqAndNode: Long): Uuid = new Uuid(time, clockSeqAndNode)

  def uuidFrom(uuid: String): Uuid = new Uuid(uuid)

  def simpleName(obj: AnyRef): String = {
    val n = obj.getClass.getName
    val i = n.lastIndexOf('.')
    n.substring(i + 1)
  }

  def simpleName(clazz: Class[_]): String = {
    val n = clazz.getName
    val i = n.lastIndexOf('.')
    n.substring(i + 1)
  }

  implicit def future2actor[T](f: akka.dispatch.Future[T]) = new {
    def pipeTo(actor: ActorRef): this.type = {
      f onComplete {
        case Right(r) ⇒ actor ! r
        case Left(f)  ⇒ actor ! Status.Failure(f)
      }
      this
    }
  }

}

package object patterns {

  import akka.actor.{ ActorRef, InternalActorRef }
  import akka.dispatch.Promise
  import akka.util.Timeout

  implicit def ask(actorRef: ActorRef): AskableActorRef = new AskableActorRef()(actorRef)

  // Implicit for converting a Promise to an ActorRef.
  // Symmetric to the future2actor conversion, which allows
  // piping a Future result (read side) to an Actor's mailbox, this
  // conversion allows using an Actor to complete a Promise (write side)
  //
  // Future.ask / actor ? message is now a trivial implementation that can
  // also be done in user code (assuming actorRef, timeout and dispatcher implicits):
  //
  // Patterns.ask(actor, message) = {
  //   val promise = Promise[Any]()
  //   actor ! (message, promise)
  //   promise
  // }

  @inline implicit def promise2actorRef(promise: Promise[Any])(implicit actorRef: ActorRef, timeout: Timeout): ActorRef = {
    val provider = actorRef.asInstanceOf[InternalActorRef].provider
    provider.ask(promise, timeout) match {
      case Some(ref) ⇒ ref
      case None      ⇒ null
    }
  }

}

package patterns {

  import akka.actor.{ ActorRef, InternalActorRef }
  import akka.dispatch.{ Future, Promise }
  import akka.util.Timeout

  final class AskableActorRef(implicit val actorRef: ActorRef) {

    /**
     * Akka Java API.
     *
     * Sends a message asynchronously returns a future holding the eventual reply message.
     * The Future will be completed with an [[akka.actor.AskTimeoutException]] after the given
     * timeout has expired.
     *
     * <b>NOTE:</b>
     * Use this method with care. In most cases it is better to use 'tell' together with the sender
     * parameter to implement non-blocking request/response message exchanges.
     *
     * If you are sending messages using <code>ask</code> and using blocking operations on the Future, such as
     * 'get', then you <b>have to</b> use <code>getContext().sender().tell(...)</code>
     * in the target actor to send a reply message to the original sender, and thereby completing the Future,
     * otherwise the sender will block until the timeout expires.
     *
     * When using future callbacks, inside actors you need to carefully avoid closing over
     * the containing actor’s reference, i.e. do not call methods or access mutable state
     * on the enclosing actor from within the callback. This would break the actor
     * encapsulation and may introduce synchronization bugs and race conditions because
     * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
     * there is not yet a way to detect these illegal accesses at compile time.
     */
    def ask(message: AnyRef, timeout: Timeout): Future[AnyRef] = ?(message, timeout).asInstanceOf[Future[AnyRef]]

    def ask(message: AnyRef, timeoutMillis: Long): Future[AnyRef] = ask(message, new Timeout(timeoutMillis))

    /**
     * Sends a message asynchronously, returning a future which may eventually hold the reply.
     * The Future will be completed with an [[akka.actor.AskTimeoutException]] after the given
     * timeout has expired.
     *
     * <b>NOTE:</b>
     * Use this method with care. In most cases it is better to use '!' together with implicit or explicit
     * sender parameter to implement non-blocking request/response message exchanges.
     *
     * If you are sending messages using <code>ask</code> and using blocking operations on the Future, such as
     * 'get', then you <b>have to</b> use <code>getContext().sender().tell(...)</code>
     * in the target actor to send a reply message to the original sender, and thereby completing the Future,
     * otherwise the sender will block until the timeout expires.
     *
     * When using future callbacks, inside actors you need to carefully avoid closing over
     * the containing actor’s reference, i.e. do not call methods or access mutable state
     * on the enclosing actor from within the callback. This would break the actor
     * encapsulation and may introduce synchronization bugs and race conditions because
     * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
     * there is not yet a way to detect these illegal accesses at compile time.
     */
    def ?(message: Any)(implicit timeout: Timeout): Future[Any] = {
      implicit val dispatcher = actorRef.asInstanceOf[InternalActorRef].provider.dispatcher
      val promise = Promise[Any]()
      actorRef.!(message)(promise)
      promise
    }

    /**
     * Sends a message asynchronously, returning a future which may eventually hold the reply.
     * The implicit parameter with the default value is just there to disambiguate it from the version that takes the
     * implicit timeout
     */
    def ?(message: Any, timeout: Timeout)(implicit ignore: Int = 0): Future[Any] = ?(message)(timeout)
  }

}

object Patterns {

  import akka.actor.ActorRef
  import akka.dispatch.Future
  import akka.patterns.{ ask => actorRef2Askable }
  import akka.util.Timeout

  def ask(actor: ActorRef, message: Any, timeout: Timeout): Future[Any] =
    actorRef2Askable(actor).?(message)(timeout)

  def ask(actor: ActorRef, message: Any, timeoutMillis: Long): Future[Any] =
    actorRef2Askable(actor).?(message)(new Timeout(timeoutMillis))

}
