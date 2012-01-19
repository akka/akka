package akka

import actor.ActorSystem

package object zeromq {
  implicit def zeromqSystem(system: ActorSystem) = new {
    def zeromq = system.extension(ZeroMQExtension)
  }

  val SubscribeAll = Subscribe(Seq.empty)
}