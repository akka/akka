package sample.distributeddata

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey

object ReplicatedCache {
  import akka.cluster.ddata.Replicator._

  def props: Props = Props[ReplicatedCache]

  private final case class Request(key: String, replyTo: ActorRef)

  final case class PutInCache(key: String, value: Any)
  final case class GetFromCache(key: String)
  final case class Cached(key: String, value: Option[Any])
  final case class Evict(key: String)
}

class ReplicatedCache extends Actor {
  import akka.cluster.ddata.Replicator._
  import ReplicatedCache._

  val replicator = DistributedData(context.system).replicator
  implicit val cluster = Cluster(context.system)

  def dataKey(entryKey: String): LWWMapKey[Any] =
    LWWMapKey("cache-" + math.abs(entryKey.hashCode) % 100)

  def receive = {
    case PutInCache(key, value) ⇒
      replicator ! Update(dataKey(key), LWWMap(), WriteLocal)(_ + (key -> value))
    case Evict(key) ⇒
      replicator ! Update(dataKey(key), LWWMap(), WriteLocal)(_ - key)
    case GetFromCache(key) ⇒
      replicator ! Get(dataKey(key), ReadLocal, Some(Request(key, sender())))
    case g @ GetSuccess(LWWMapKey(_), Some(Request(key, replyTo))) ⇒
      g.dataValue match {
        case data: LWWMap[_] ⇒ data.get(key) match {
          case Some(value) ⇒ replyTo ! Cached(key, Some(value))
          case None        ⇒ replyTo ! Cached(key, None)
        }
      }
    case NotFound(_, Some(Request(key, replyTo))) ⇒
      replyTo ! Cached(key, None)
    case _: UpdateResponse[_] ⇒ // ok
  }

}
