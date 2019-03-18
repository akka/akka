/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.cluster.sharding.ShardRegion.EntityId
import akka.testkit.{ AkkaSpec, TimingTest }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class ConstantRateEntityRecoveryStrategySpec extends AkkaSpec {

  val strategy = EntityRecoveryStrategy.constantStrategy(system, 1.second, 2)
  "ConstantRateEntityRecoveryStrategy" must {
    "recover entities" taggedAs TimingTest in {
      import system.dispatcher
      val entities = Set[EntityId]("1", "2", "3", "4", "5")
      val startTime = System.nanoTime()
      val resultWithTimes =
        strategy.recoverEntities(entities).map(_.map(entityIds => entityIds -> (System.nanoTime() - startTime).nanos))

      val result =
        Await.result(Future.sequence(resultWithTimes), 6.seconds).toVector.sortBy { case (_, duration) => duration }
      result.size should ===(3)

      val scheduledEntities = result.map(_._1)
      scheduledEntities(0).size should ===(2)
      scheduledEntities(1).size should ===(2)
      scheduledEntities(2).size should ===(1)
      scheduledEntities.flatten.toSet should ===(entities)

      val timesMillis = result.map(_._2.toMillis)

      // scheduling will not happen too early
      timesMillis(0) should ===(1400L +- 500)
      timesMillis(1) should ===(2400L +- 500L)
      timesMillis(2) should ===(3400L +- 500L)
    }

    "not recover when no entities to recover" in {
      val result = strategy.recoverEntities(Set[EntityId]())
      result.size should ===(0)
    }
  }
}
