/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.io.File

import scala.concurrent.duration._

import akka.actor.{ Actor, ActorIdentity, ActorRef, ActorSystem, Identify, PoisonPill, Props }
import akka.cluster.{ Cluster, MultiNodeClusterSpec }
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.TestProbe
import akka.util.ccompat.ccompatUsedUntil213
import org.apache.commons.io.FileUtils

@ccompatUsedUntil213
object MultiNodeClusterShardingSpec {

  final case class EntityStarted(ref: ActorRef)

  def props(probe: ActorRef): Props = Props(new EntityActor(probe))

  class EntityActor(probe: ActorRef) extends Actor {
    probe ! EntityStarted(self)

    def receive: Receive = {
      case m => sender() ! m
    }
  }

  val defaultExtractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int => (id.toString, id)
  }

  val defaultExtractShardId: ShardRegion.ExtractShardId = msg =>
    msg match {
      case id: Int                     => id.toString
      case ShardRegion.StartEntity(id) => id
    }

}

abstract class MultiNodeClusterShardingSpec(val config: MultiNodeClusterShardingConfig)
    extends MultiNodeSpec(config)
    with MultiNodeClusterSpec {

  import MultiNodeClusterShardingSpec._
  import config._

  override def initialParticipants: Int = roles.size

  protected val storageLocations = List(
    new File(system.settings.config.getString("akka.cluster.sharding.distributed-data.durable.lmdb.dir")).getParentFile)

  override protected def atStartup(): Unit = {
    storageLocations.foreach(dir => if (dir.exists) FileUtils.deleteQuietly(dir))
    enterBarrier("startup")
    super.atStartup()
  }

  override protected def afterTermination(): Unit = {
    storageLocations.foreach(dir => if (dir.exists) FileUtils.deleteQuietly(dir))
    super.afterTermination()
  }

  protected def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system).join(node(to).address)
      awaitAssert {
        Cluster(system).state.isMemberUp(node(from).address)
      }
    }
    enterBarrier(from.name + "-joined")
  }

  protected def startSharding(
      sys: ActorSystem,
      entityProps: Props,
      dataType: String,
      extractEntityId: ShardRegion.ExtractEntityId = defaultExtractEntityId,
      extractShardId: ShardRegion.ExtractShardId = defaultExtractShardId,
      handOffStopMessage: Any = PoisonPill): ActorRef = {

    ClusterSharding(sys).start(
      typeName = dataType,
      entityProps = entityProps,
      settings = ClusterShardingSettings(sys).withRememberEntities(rememberEntities),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId,
      ClusterSharding(sys).defaultShardAllocationStrategy(ClusterShardingSettings(sys)),
      handOffStopMessage)
  }

  protected def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  private def setStoreIfNotDdataMode(sys: ActorSystem, storeOn: RoleName): Unit =
    if (!isDdataMode) {
      val probe = TestProbe()(sys)
      sys.actorSelection(node(storeOn) / "user" / "store").tell(Identify(None), probe.ref)
      val sharedStore = probe.expectMsgType[ActorIdentity](20.seconds).ref.get
      SharedLeveldbJournal.setStore(sharedStore, sys)
    }

  /**
   * {{{
   *    startPersistence(startOn = first, setStoreOn = Seq(first, second, third))
   * }}}
   *
   * @param startOn the node to start the `SharedLeveldbStore` store on
   * @param setStoreOn the nodes to `SharedLeveldbJournal.setStore` on
   */
  protected def startPersistenceIfNotDdataMode(startOn: RoleName, setStoreOn: Seq[RoleName]): Unit =
    if (!isDdataMode) {

      Persistence(system)
      runOn(startOn) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("persistence-started")

      runOn(setStoreOn: _*) {
        setStoreIfNotDdataMode(system, startOn)
      }

      enterBarrier(s"after-${startOn.name}")

    }

}
