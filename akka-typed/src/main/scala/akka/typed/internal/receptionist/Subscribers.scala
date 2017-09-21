package akka.typed.internal.receptionist

import akka.typed.ActorRef
import akka.typed.receptionist.Receptionist.Listing
import akka.typed.receptionist.Receptionist.ServiceKey

import scala.collection.immutable

import Subscriptions._

private[internal] trait Subscriptions {
  def add[T](key: ServiceKey[T], subscriber: Subscriber[T]): Subscriptions
  def remove[T](key: ServiceKey[T], subscriber: Subscriber[T]): Subscriptions
  def get[T](key: ServiceKey[T]): immutable.Set[Subscriber[T]]
}
private[internal] object Subscriptions {
  type Subscriber[T] = ActorRef[Listing[T]]

  val empty: Subscriptions = SubscriptionsImpl(Map.empty)

  final case class SubscriptionsImpl(map: Map[ServiceKey[_], immutable.Set[ActorRef[_]]]) extends Subscriptions {
    def add[T](key: ServiceKey[T], subscriber: Subscriber[T]): Subscriptions =
      copy(map = map.updated(key, map.getOrElse(key, Set.empty) + subscriber))

    def remove[T](key: ServiceKey[T], subscriber: ActorRef[Listing[T]]): Subscriptions =
      copy(map = map.updated(key, map.getOrElse(key, Set.empty) - subscriber))

    def get[T](key: ServiceKey[T]): immutable.Set[ActorRef[Listing[T]]] =
      map.getOrElse(key, Set.empty).asInstanceOf[immutable.Set[ActorRef[Listing[T]]]]
  }
}