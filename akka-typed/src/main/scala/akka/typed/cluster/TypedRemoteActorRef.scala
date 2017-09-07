package akka.typed.cluster

import akka.{ actor ⇒ a }
import akka.remote.RemoteActorRef
import akka.remote.RemoteTransport
import akka.typed.internal.adapter.ActorRefAdapter

private[akka] class TypedRemoteActorRef[T](
  untypedRemoteRef: RemoteActorRef,
  remote:           RemoteTransport)
  extends ActorRefAdapter(untypedRemoteRef: RemoteActorRef) {

  override def tell(message: Any): Unit =
    untypedRemoteRef.tell(message, a.ActorRef.noSender)

}
