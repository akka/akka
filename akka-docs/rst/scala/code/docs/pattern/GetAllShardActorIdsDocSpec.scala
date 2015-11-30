package docs.pattern



class GetAllShardActorIdsDocSpec {
  import akka.actor.ActorSystem
  import akka.actor.Address
  import akka.pattern.ask
  import akka.cluster.sharding.ClusterSharding
  import akka.cluster.sharding.ShardRegion.GetCurrentRegions
  import akka.cluster.sharding.ShardRegion.CurrentRegions
  import akka.cluster.sharding.ShardRegion.GetShardRegionState
  import akka.cluster.sharding.ShardRegion.CurrentShardRegionState
  import akka.util.Timeout
  import scala.concurrent.Future
  import scala.concurrent.ExecutionContext
  import scala.concurrent.duration._

  implicit val timeout: Timeout = 2.seconds
  val system: ActorSystem = ???
  implicit val ec: ExecutionContext = system.dispatcher
  val shardTypeName: String = "???"
  val currentRegions = (ClusterSharding(system).shardRegion(shardTypeName) ? GetCurrentRegions).mapTo[CurrentRegions].map(_.regions)

  def addressToFutureShardIds(region: Address): Future[Set[String]] = {
    val shardState = (ClusterSharding(system).remoteShardRegion(region, shardTypeName) ? GetShardRegionState).mapTo[CurrentShardRegionState].map(_.shards)
    shardState.map(_.flatMap(_.entityIds))
  }

  def toShardIds(regions: Set[Address]): Future[Set[String]] = Future.sequence(regions.map(addressToFutureShardIds)).map(_.flatten)

  val futureIds: Future[Set[String]] = currentRegions.flatMap(toShardIds)
  futureIds.onSuccess { case msg => println(s"Shard[$shardTypeName] contains IDS: $msg") }

}
