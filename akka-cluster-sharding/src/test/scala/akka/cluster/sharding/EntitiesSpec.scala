/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding
import akka.actor.ActorRef
import akka.cluster.sharding
import akka.cluster.sharding.Shard.{
  Active,
  NoState,
  Passivating,
  RememberedButNotCreated,
  RememberingStart,
  RememberingStop
}
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
        entities.entityState(id) shouldEqual RememberedButNotCreated
      }
    }

    "set state to remembering start" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      entities.rememberingStart("a", None)
      entities.entityState("a") shouldEqual RememberingStart(None)
      entities.pendingRememberedEntitiesExist() should ===(true)
      val (starts, stops) = entities.pendingRememberEntities()
      starts.keySet should contain("a")
      stops should be(empty)
    }
    "set state to remembering stop" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      entities.addEntity("a", ActorRef.noSender) // need to go through active to passivate
      entities.entityPassivating("a") // need to go through passivate to remember stop
      entities.rememberingStop("a", Passivating)
      entities.entityState("a") shouldEqual RememberingStop(Passivating)
      entities.pendingRememberedEntitiesExist() should ===(true)
      val (starts, stops) = entities.pendingRememberEntities()
      stops should contain("a")
      starts should be(empty)
    }

    "fully remove an entity" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      val ref = ActorRef.noSender
      entities.addEntity("a", ref)
      entities.entityPassivating("a") // needs to go through passivating to be removed
      entities.removeEntity("a")
      entities.entityState("a") shouldEqual NoState
      entities.activeEntities() shouldEqual Set.empty

    }
    "add an entity as active" in {
      val entities = new sharding.Shard.Entities(NoLogging)
      val ref = ActorRef.noSender
      entities.addEntity("a", ref)
      entities.entityState("a") shouldEqual Active(ref)
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
      entities.entityState("a") shouldEqual Passivating(ref)
    }
  }

}
