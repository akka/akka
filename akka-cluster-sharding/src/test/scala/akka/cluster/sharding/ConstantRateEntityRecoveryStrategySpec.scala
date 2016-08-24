package akka.cluster.sharding

import akka.cluster.sharding.ShardRegion.EntityId
import akka.testkit.AkkaSpec

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

class ConstantRateEntityRecoveryStrategySpec extends AkkaSpec {

  import system.dispatcher

  val strategy = EntityRecoveryStrategy.constantStrategy(system, 500 millis, 2)

  "ConstantRateEntityRecoveryStrategy" must {
    "recover entities" in {
      val entities = Set[EntityId]("1", "2", "3", "4", "5")
      val startTime = System.currentTimeMillis()
      val resultWithTimes = strategy.recoverEntities(entities).map(
        _.map(entityIds ⇒ (entityIds, System.currentTimeMillis() - startTime))
      )

      val result = Await.result(Future.sequence(resultWithTimes), 4 seconds).toList.sortWith(_._2 < _._2)
      result.size should ===(3)

      val scheduledEntities = result.map(_._1)
      scheduledEntities.head.size should ===(2)
      scheduledEntities(1).size should ===(2)
      scheduledEntities(2).size should ===(1)
      scheduledEntities.foldLeft(Set[EntityId]())(_ ++ _) should ===(entities)

      val times = result.map(_._2)

      times.head should ===(500L +- 30L)
      times(1) should ===(1000L +- 30L)
      times(2) should ===(1500L +- 30L)
    }

    "not recover when no entities to recover" in {
      val result = strategy.recoverEntities(Set[EntityId]())
      result.size should ===(0)
    }
  }
}
