/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.Done
import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorLogging
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.internal.RememberEntitiesShardStore
import akka.testkit.TestException
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import akka.pattern.after
import akka.testkit.WithLogCapturing

import scala.concurrent.duration._
import scala.concurrent.Future

object RememberEntitiesFailureSpec {
  val config = ConfigFactory.parseString(s"""
      akka.loglevel = DEBUG
      akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
      akka.actor.provider = cluster
      akka.cluster.sharding.distributed-data.durable.keys = []
      akka.cluster.sharding.custom-store = "akka.cluster.sharding.RememberEntitiesFailureSpec$$FakeStore"
      # quick backoff for stop tests
      akka.cluster.sharding.entity-restart-backoff = 1s
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
  case object Immediate extends Fail
  case object InFutureImmediate extends Fail
  case object InFutureLater extends Fail

  // outside store since shard allocation triggers initaialization of store
  @volatile var failInitial = Map.empty[ShardId, Fail]

  case class StoreCreated(store: FakeStore, shardId: ShardId)
  case class FakeStore(shardId: ShardId, shardContext: ActorContext) extends RememberEntitiesShardStore {
    implicit val ec = shardContext.system.dispatcher
    @volatile var failAddEntity = Map.empty[EntityId, Fail]
    @volatile var failRemoveEntity = Map.empty[EntityId, Fail]

    shardContext.system.eventStream.publish(StoreCreated(this, shardId))
    override def getEntities(shardId: ShardId): Future[Set[EntityId]] = {
      failInitial.get(shardId) match {
        case None                    => Future.successful(Set.empty)
        case Some(Immediate)         => throw TestException("immediate fail")
        case Some(InFutureImmediate) => Future.failed(TestException("future immediately failed"))
        case Some(InFutureLater) =>
          after(50.millis, shardContext.system.scheduler)(Future.failed(TestException("future failing later")))
      }
    }

    override def addEntity(shardId: ShardId, entityId: EntityId): Future[Done] =
      failAddEntity.get(entityId) match {
        case None                    => Future.successful(Done)
        case Some(Immediate)         => throw TestException("immediate fail")
        case Some(InFutureImmediate) => Future.failed(TestException("future immediately failed"))
        case Some(InFutureLater) =>
          after(50.millis, shardContext.system.scheduler)(Future.failed(TestException("future failing later")))
      }

    override def removeEntity(shardId: ShardId, entityId: EntityId): Future[Done] =
      failRemoveEntity.get(entityId) match {
        case None                    => Future.successful(Done)
        case Some(Immediate)         => throw TestException("immediate fail")
        case Some(InFutureImmediate) => Future.failed(TestException("future immediately failed"))
        case Some(InFutureLater) =>
          after(50.millis, shardContext.system.scheduler)(Future.failed(TestException("future failing later")))
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

    List(Immediate, InFutureImmediate, InFutureLater).foreach { wayToFail: Fail =>
      // FIXME Immediate does not work, should it?
      if (wayToFail != Immediate) {
        s"recover when initial remember entities load fails $wayToFail" in {
          failInitial = Map("1" -> wayToFail)

          try {
            val probe = TestProbe()
            val sharding = ClusterSharding(system).start(
              s"initial-$wayToFail",
              Props[EntityActor],
              ClusterShardingSettings(system).withRememberEntities(true),
              extractEntityId,
              extractShardId)

            // FIXME why two error logs here?
            EventFilter[TestException](occurrences = 2).intercept {
              sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
            }
            probe.expectNoMessage() // message is lost because shard crashes

            failInitial = Map.empty

            // shard should be restarted and eventually succeed
            awaitAssert {
              sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
              probe.expectMsg("hello-1")
            }

            system.stop(sharding)
          } finally {
            failInitial = Map.empty
          }
        }
      }

      s"recover when storing a start event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[StoreCreated])

        val sharding = ClusterSharding(system).start(
          s"storeStart-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId)

        // trigger shard start and store creation
        val probe = TestProbe()
        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        val shard1Store = storeProbe.expectMsgType[StoreCreated].store
        probe.expectMsg("hello-1")

        // hit shard with other entity that will fail
        shard1Store.failAddEntity = Map("11" -> wayToFail)
        EventFilter[RuntimeException](occurrences = 1).intercept {
          sharding.tell(EntityEnvelope(11, "hello-11"), probe.ref)
        }
        // do we get an answer here? shard crashes
        probe.expectNoMessage()

        shard1Store.failAddEntity = Map.empty
        awaitAssert {
          sharding.tell(EntityEnvelope(11, "hello-11-2"), probe.ref)
          probe.expectMsg("hello-11-2")
        }
        system.stop(sharding)
      }

      s"recover on abrupt entity stop when storing a stop event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[StoreCreated])

        val sharding = ClusterSharding(system).start(
          s"storeStopAbrupt-$wayToFail",
          Props[EntityActor],
          ClusterShardingSettings(system).withRememberEntities(true),
          extractEntityId,
          extractShardId)

        val probe = TestProbe()

        // trigger shard start and store creation
        sharding.tell(EntityEnvelope(1, "hello-1"), probe.ref)
        val shard1Store = storeProbe.expectMsgType[StoreCreated].store
        probe.expectMsg("hello-1")

        // fail it when stopping
        shard1Store.failRemoveEntity = Map("1" -> wayToFail)

        // FIXME restart without passivating is not saved and re-started again without storing the stop so this isn't testing anything
        sharding ! EntityEnvelope(1, "stop")

        shard1Store.failRemoveEntity = Map.empty

        // it takes a while?
        awaitAssert({
          sharding.tell(EntityEnvelope(1, "hello-2"), probe.ref)
          probe.expectMsg("hello-2")
        }, 5.seconds)
        system.stop(sharding)
      }

      s"recover on graceful entity stop when storing a stop event fails $wayToFail" in {
        val storeProbe = TestProbe()
        system.eventStream.subscribe(storeProbe.ref, classOf[StoreCreated])

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
        val shard1Store = storeProbe.expectMsgType[StoreCreated].store
        probe.expectMsg("hello-1")

        // fail it when stopping
        shard1Store.failRemoveEntity = Map("1" -> wayToFail)

        EventFilter[RuntimeException](occurrences = 1).intercept {
          sharding ! EntityEnvelope(1, "graceful-stop")
        }
        shard1Store.failRemoveEntity = Map.empty

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
