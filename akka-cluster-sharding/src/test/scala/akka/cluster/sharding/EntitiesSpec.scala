/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding
import akka.actor.ActorRef
import akka.cluster.sharding
import akka.cluster.sharding.Shard.{ Active, Passivating, RememberedButNotCreated, Remembering, Stopped }
import akka.event.NoLogging
import akka.util.OptionVal
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EntitiesSpec extends AnyWordSpec with Matchers {

  "Entities" should {
    "start empty" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      entities.activeEntityIds() shouldEqual Set.empty
      entities.size shouldEqual 0
      entities.activeEntities() shouldEqual Set.empty
    }
    "set already remembered entities to state RememberedButNotStarted" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      val ids = Set("a", "b", "c")
      entities.alreadyRemembered(ids)
      entities.activeEntities() shouldEqual Set.empty
      entities.size shouldEqual 3
      ids.foreach { id =>
        entities.entityState(id) shouldEqual OptionVal.Some(RememberedButNotCreated)
      }
    }
    "set state to terminating" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      val ref = ActorRef.noSender
      entities.addEntity("a", ref)
      entities.terminated(ref)
      entities.entityState("a") shouldEqual OptionVal.Some(Stopped)
    }

    "set state to remembering" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      entities.remembering("a")
      entities.entityState("a") shouldEqual OptionVal.Some(Remembering)
    }
    "fully remove an entity" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      val ref = ActorRef.noSender
      entities.addEntity("a", ref)
      entities.removeEntity("a")
      entities.entityState("a") shouldEqual OptionVal.None
      entities.activeEntities() shouldEqual Set.empty

    }
    "add an entity as active" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      val ref = ActorRef.noSender
      entities.addEntity("a", ref)
      entities.entityState("a") shouldEqual OptionVal.Some(Active(ref))
    }
    "look up actor ref by id" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      val ref = ActorRef.noSender
      entities.addEntity("a", ref)
      entities.entityId(ref) shouldEqual OptionVal.Some("a")
    }
    "set state to passivating" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      val ref = ActorRef.noSender
      entities.addEntity("a", ref)
      entities.entityPassivating("a")
      entities.entityState("a") shouldEqual OptionVal.Some(Passivating(ref))
    }
  }

}
