/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics

import akka.testkit.AkkaSpec

/**
 * Convinience trait for using [[MetricsKit]] in [[akka.testkit.AkkaSpec]] backed tests.
 * Metrics configuration is used from the AkkaSpecs' actor systems' configuration.
 *
 * The reason MetricsKit does not require AkkaSpec (or an actor system) out of the box,
 * is in order to allow using metrics in code that does not depend on actors (plain queues etc).
 */
trait AkkaMetricsKit extends MetricsKit {
  this: AkkaSpec ⇒

  override def metricsConfig = system.settings.config
}
