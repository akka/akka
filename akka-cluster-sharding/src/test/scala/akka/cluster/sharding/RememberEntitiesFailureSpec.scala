/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.internal.RememberEntitiesCoordinatorStore
import akka.cluster.sharding.internal.RememberEntitiesShardStore
import akka.cluster.sharding.internal.RememberEntitiesProvider
import akka.testkit.AkkaSpec
import akka.testkit.TestException
import akka.testkit.TestProbe
import akka.testkit.WithLogCapturing
import com.github.ghik.silencer.silent
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object RememberEntitiesFailureSpec {
  val config = ConfigFactory.parseString(s"""
      akka.loglevel = DEBUG
      akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
      akka.actor.provider = cluster
      akka.remote.artery.canonical.port = 0
      akka.remote.classic.netty.tcp.port = 0
      akka.cluster.sharding.distributed-data.durable.keys = []
      akka.cluster.sharding.state-store-mode = custom
      akka.cluster.sharding.custom-store = "akka.cluster.sharding.RememberEntitiesFailureSpec$$FakeStore"
      # quick backoffs
      akka.cluster.sharding.entity-restart-backoff = 1s
      akka.cluster.sharding.shard-failure-backoff = 1s
    """)

  class EntityActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case "stop" =>
        log.info("Stopping myself!")
        context.stop(self)
      case "graceful-stop" =>
        context.parent ! ShardRegion.Passivate("stop")
      case msg => sender() ! msg
    }
  }

  case class EntityEnvelope(entityId: Int, msg: Any)

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id.toString, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) => (id % 10).toString
  }

  sealed trait Fail
  case object NoResponse extends Fail
  case object CrashStore extends Fail

  // outside store since we need to be able to set them before sharding initializes
  @volatile var failShardGetEntities = Map.empty[ShardId, Fail]
  @volatile var failCoordinatorGetShards: Option[Fail] = None

  case class ShardStoreCreated(store: ActorRef, shardId: ShardId)
  case class CoordinatorStoreCreated(store: ActorRef)

  @silent("never used")
  class FakeStore(settings: ClusterShardingSettings, typeName: String) extends RememberEntitiesProvider {
    override def shardStoreProps(shardId: ShardId): Props = FakeShardStoreActor.props(shardId)
    override def coordinatorStoreProps(): Props = FakeCoordinatorStoreActor.props()
  }

  object FakeShardStoreActor {
    def props(shardId: ShardId): Props = Props(new FakeShardStoreActor(shardId))

    case class FailAddEntity(entityId: EntityId, whichWay: Fail)
    case class DoNotFailAddEntity(entityId: EntityId)
    case class FailRemoveEntity(entityId: EntityId, whichWay: Fail)
    case class DoNotFailRemoveEntity(entityId: EntityId)
  }
  class FakeShardStoreActor(shardId: ShardId) extends Actor with ActorLogging {
    import FakeShardStoreActor._

    implicit val ec = context.system.dispatcher
    private var failAddEntity = Map.empty[EntityId, Fail]
    private var failRemoveEntity = Map.empty[EntityId, Fail]

    context.system.eventStream.publish(ShardStoreCreated(self, shardId))

    override def receive: Receive = {
      case RememberEntitiesShardStore.GetEntities =>
        failShardGetEntities.get(shardId) match {
          case None             => sender ! RememberEntitiesShardStore.RememberedEntities(Set.empty)
          case Some(NoResponse) => log.debug("Sending no response for GetEntities")
          case Some(CrashStore) => throw TestException("store crash on GetEntities")
        }
      case RememberEntitiesShardStore.AddEntity(entityId) =>
        failAddEntity.get(entityId) match {
          case None             => sender ! RememberEntitiesShardStore.UpdateDone(entityId)
          case Some(NoResponse) => log.debug("Sending no response for AddEntity")
          case Some(CrashStore) => throw TestException("store crash on AddEntity")
        }
      case RememberEntitiesShardStore.RemoveEntity(entityId) =>
        failRemoveEntity.get(entityId) match {
          case None             => sender ! RememberEntitiesShardStore.UpdateDone(entityId)
          case Some(NoResponse) => log.debug("Sending no response for RemoveEntity")
          case Some(CrashStore) => throw TestException("store crash on AddEntity")
        }
      case FailAddEntity(id, whichWay) =>
        failAddEntity = failAddEntity.updated(id, whichWay)
        sender() ! Done
      case DoNotFailAddEntity(id) =>
        failAddEntity = failAddEntity - id
        sender() ! Done
      case FailRemoveEntity(id, whichWay) =>
        failRemoveEntity = failRemoveEntity.updated(id, whichWay)
        sender() ! Done
      case DoNotFailRemoveEntity(id) =>
        failRemoveEntity = failRemoveEntity - id
        sender() ! Done

    }
  }

  object FakeCoordinatorStoreActor {
    def props(): Props = Props(new FakeCoordinatorStoreActor)

  }
  class FakeCoordinatorStoreActor extends Actor with ActorLogging {

    context.system.eventStream.publish(CoordinatorStoreCreated(context.self))

    override def receive: Receive = {
      case RememberEntitiesCoordinatorStore.GetShards =>
        failCoordinatorGetShards match {
          case None             => sender() ! RememberEntitiesCoordinatorStore.RememberedShards(Set.empty)
          case Some(NoResponse) =>
          case Some(CrashStore) =>
            throw TestException("store crash on load")
        }
      case RememberEntitiesCoordinatorStore.AddShard(shardId) =>
    }
  }

}

class RememberEntitiesFailureSpec
    extends AkkaSpec(RememberEntitiesFailureSpec.config)
    with AnyWordSpecLike
    with WithLogCapturing {

  import RememberEntitiesFailureSpec._

  override def atStartup(): Unit = {
    // Form a one node cluster
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
    awaitAssert(cluster.readView.members.count(_.status == MemberStatus.Up) should ===(1))
  }

  "Remember entities handling in sharding" must {

    List(NoResponse, CrashStore).foreach { wayToFail: Fail =>
      s"recover when initial remember entities load fails $wayToFail" in {
        log.debug("Getting entities for shard 1 will fail")
        failShardGetEntities = Map("1" -> wayToFail)

        try {
          val probe = TestProbe()
          val sharding = ClusterSharding(system).start(
            s"initial-$wayToFail",
            Props[EntityActor],
            ClusterShardingSettings(system).withRememberEntities(true),
            extractEntityId,
            extractShardId)

          sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
          probe.expectNoMessage() // message is lost because shard crashes

          log.debug("Resetting initial fail")
          failShardGetEntities = Map.empty

          // shard should be restarted and eventually succeed
          awaitAssert {
            sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
            probe.expectMsg("hello-1")
          }

          system.stop(sharding)
        } finally {
          failShardGetEntities = Map.empty
        }
      }

      s"recover when storing a start event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[ShardStoreCreated])

        val sharding = ClusterSharding(system).start(
          s"storeStart-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId)

        // trigger shard start and store creation
        val probe = TestProbe()
        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        val shard1Store = storeProbe.expectMsgType[ShardStoreCreated].store
        probe.expectMsg("hello-1")

        // hit shard with other entity that will fail
        shard1Store.tell(FakeShardStoreActor.FailAddEntity("11", wayToFail), storeProbe.ref)
        storeProbe.expectMsg(Done)

        sharding.tell(EntityEnvelope(11, "hello-11"), probe.ref)

        // do we get an answer here? shard crashes
        probe.expectNoMessage()

        val stopFailingProbe = TestProbe()
        shard1Store.tell(FakeShardStoreActor.DoNotFailAddEntity("11"), stopFailingProbe.ref)
        stopFailingProbe.expectMsg(Done)

        // it takes a while - timeout hits and then backoff
        awaitAssert({
          sharding.tell(EntityEnvelope(11, "hello-11-2"), probe.ref)
          probe.expectMsg("hello-11-2")
        }, 10.seconds)
        system.stop(sharding)
      }

      s"recover on abrupt entity stop when storing a stop event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[ShardStoreCreated])

        val sharding = ClusterSharding(system).start(
          s"storeStopAbrupt-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId)

        val probe = TestProbe()

        // trigger shard start and store creation
        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        val shard1Store = storeProbe.expectMsgType[ShardStoreCreated].store
        probe.expectMsg("hello-1")

        // fail it when stopping
        shard1Store.tell(FakeShardStoreActor.FailRemoveEntity("1", wayToFail), storeProbe.ref)
        storeProbe.expectMsg(Done)

        // FIXME restart without passivating is not saved and re-started again without storing the stop so this isn't testing anything
        sharding ! EntityEnvelope(1, "stop")

        shard1Store.tell(FakeShardStoreActor.DoNotFailRemoveEntity("1"), storeProbe.ref)
        storeProbe.expectMsg(Done)

        // it takes a while - timeout hits and then backoff
        awaitAssert({
          sharding.tell(EntityEnvelope(1, "hello-2"), probe.ref)
          probe.expectMsg("hello-2")
        }, 10.seconds)
        system.stop(sharding)
      }

      s"recover on graceful entity stop when storing a stop event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[ShardStoreCreated])

        val sharding = ClusterSharding(system).start(
          s"storeStopGraceful-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId,
          new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 3),
          "graceful-stop")

        val probe = TestProbe()

        // trigger shard start and store creation
        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        val shard1Store = storeProbe.expectMsgType[ShardStoreCreated].store
        probe.expectMsg("hello-1")

        // fail it when stopping
        shard1Store.tell(FakeShardStoreActor.FailRemoveEntity("1", wayToFail), storeProbe.ref)
        storeProbe.expectMsg(Done)

        sharding ! EntityEnvelope(1, "graceful-stop")

        shard1Store.tell(FakeShardStoreActor.DoNotFailRemoveEntity("1"), storeProbe.ref)
        storeProbe.expectMsg(Done)

        // it takes a while?
        awaitAssert({
          sharding.tell(EntityEnvelope(1, "hello-2"), probe.ref)
          probe.expectMsg("hello-2")
        }, 5.seconds)
        system.stop(sharding)
      }
    }
  }

}
