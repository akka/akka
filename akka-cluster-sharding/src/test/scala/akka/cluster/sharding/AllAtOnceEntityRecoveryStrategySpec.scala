package akka.cluster.sharding

import akka.cluster.sharding.ShardRegion.EntityId
import akka.testkit.AkkaSpec
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

class AllAtOnceEntityRecoveryStrategySpec extends AkkaSpec {
  val strategy = EntityRecoveryStrategy.allStrategy()

  import system.dispatcher

  "AllAtOnceEntityRecoveryStrategy" must {
    "recover entities" in {
      val entities = Set[EntityId]("1", "2", "3", "4", "5")
      val startTime = System.currentTimeMillis()
      val resultWithTimes = strategy.recoverEntities(entities).map(
        _.map(entityIds ⇒ (entityIds, System.currentTimeMillis() - startTime))
      )

      val result = Await.result(Future.sequence(resultWithTimes), 4 seconds).toList.sortWith(_._2 < _._2)
      result.size should ===(1)

      val scheduledEntities = result.map(_._1)
      scheduledEntities.head should ===(entities)

      val times = result.map(_._2)
      times.head should ===(0L +- 20L)
    }

    "not recover when no entities to recover" in {
      val result = strategy.recoverEntities(Set[EntityId]())
      result.size should ===(0)
    }
  }
}