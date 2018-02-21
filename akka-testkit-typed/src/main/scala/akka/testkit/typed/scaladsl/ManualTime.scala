package akka.testkit.typed.scaladsl

import com.typesafe.config.{ Config, ConfigFactory }

import akka.testkit.typed._

object ManualTime {
  val config: Config = ConfigFactory.parseString("""akka.scheduler.implementation = "akka.testkit.typed.scaladsl.ExplicitlyTriggeredScheduler"""")
}
trait ManualTime { self: TestKit ⇒
  override val scheduler: ExplicitlyTriggeredScheduler = self.system.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]
}
