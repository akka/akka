package akka.cluster.sharding

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.cluster.sharding.ShardSpec.EntityActor
import akka.testkit.AkkaSpec

object ShardSpec {
  val config = """
  akka.actor.provider = "cluster"
  akka.remote.netty.tcp.port = 0
  akka.remote.artery.canonical.port = 0
  """

  class EntityActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case msg =>
        log.info("Msg {}", msg)
    }
  }

  val numberOfShards = 5

  case class EntityEnvelope(entityId: Int, msg: Any)

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) ⇒ (id.toString, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _)               ⇒ (id % numberOfShards).toString
  }
}

class ShardSpec extends AkkaSpec(ShardSpec.config) {

  "A Cluster Shard" should {
    "" in {
      val shard = system.actorOf(Shard.props(
        "type1",
        "shard-1",
        _ => Props(new EntityActor()),
        ClusterShardingSettings(system),
        ???,
        ???,
        PoisonPill,
        system.deadLetters,
        1
      ))
    }
  }

}
