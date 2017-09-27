/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.ddata

import akka.cluster.{ ddata ⇒ dd }

package object scaladsl {
  /**
   * @see [[akka.cluster.ddata.ReplicatorSettings]].
   */
  type ReplicatorSettings = dd.ReplicatorSettings
}
