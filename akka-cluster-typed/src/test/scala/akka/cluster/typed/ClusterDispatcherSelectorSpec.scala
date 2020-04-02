/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.typed.scaladsl.DispatcherSelectorSpec
import com.typesafe.config.ConfigFactory

class ClusterDispatcherSelectorSpec
    extends DispatcherSelectorSpec(ConfigFactory.parseString("""
    akka.actor.provider = cluster
    """).withFallback(DispatcherSelectorSpec.config)) {

  // same tests as in DispatcherSelectorSpec

}
