package akka.testkit.typed.scaladsl

import com.typesafe.config.{ Config, ConfigFactory }

import akka.testkit.typed._

trait ManualTime extends TestKitMixin { self: TestKit ⇒
  override def mixedInConfig: Config =
    ConfigFactory
      .parseString("""akka.scheduler.implementation = "akka.testkit.typed.ExplicitlyTriggeredScheduler"""")
      .withFallback(super.mixedInConfig)

  override val scheduler: ExplicitlyTriggeredScheduler = self.system.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]
}
