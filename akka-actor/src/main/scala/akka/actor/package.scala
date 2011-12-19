/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

package object actor {
  implicit def actorRef2Scala(ref: ActorRef): ScalaActorRef = ref.asInstanceOf[ScalaActorRef]
  implicit def scala2ActorRef(ref: ScalaActorRef): ActorRef = ref.asInstanceOf[ActorRef]

  implicit def actorRef2Askable(actorRef: ActorRef) = new dispatch.AskableActorRef(actorRef)
  implicit def askable2ActorRef(askable: dispatch.AskableActorRef) = askable.actorRef

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

  // Implicit for converting a Promise to an actor.
  // Symmetric to the future2actor conversion, which allows
  // piping a Future result (read side) to an Actor's mailbox, this
  // conversion allows using an Actor to complete a Promise (write side)
  //
  // Future.ask / actor ? message is now a trivial implementation that can
  // also be done in user code (assuming actorRef, timeout and dispatcher implicits):
  //
  // Future.ask(actor, message) = {
  //   val promise = Promise[Any]()
  //   actor ! (message, promise)
  //   promise
  // }

  @inline implicit def promise2actor(promise: akka.dispatch.Promise[Any])(implicit actorRef: ActorRef, timeout: akka.util.Timeout) = {
    val provider = actorRef.asInstanceOf[InternalActorRef].provider
    provider.ask(promise, timeout) match {
      case Some(ref) ⇒ ref
      case None      ⇒ null
    }
  }

}
