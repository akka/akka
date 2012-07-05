/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka

package object actor {
  implicit def actorRef2Scala(ref: ActorRef): ScalaActorRef =
    ref.asInstanceOf[ScalaActorRef]

  implicit def scala2ActorRef(ref: ScalaActorRef): ActorRef =
    ref.asInstanceOf[ActorRef]

  type Uuid = com.eaio.uuid.UUID

  def newUuid(): Uuid = new Uuid()

  def uuidFrom(time: Long, clockSeqAndNode: Long): Uuid = new Uuid(time, clockSeqAndNode)

  def uuidFrom(uuid: String): Uuid = new Uuid(uuid)

  def simpleName(obj: AnyRef): String = {
    val n = obj.getClass.getName
    val i = n.lastIndexOf('.')
    n.substring(i + 1).replaceAll("\\$+", ".")
  }

  implicit def future2actor[T](f: akka.dispatch.Future[T]) = new {
    def pipeTo(channel: Channel[T]): this.type = {
      if (f.isCompleted) {
        f.value.get.fold(channel.sendException(_), channel.tryTell(_))
      } else {
        f onComplete { _.value.get.fold(channel.sendException(_), channel.tryTell(_)) }
      }
      this
    }
  }

}
