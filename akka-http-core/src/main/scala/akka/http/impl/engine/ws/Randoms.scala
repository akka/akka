/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import java.security.SecureRandom
import java.util.Random

object Randoms {
  /** A factory that creates SecureRandom instances */
  private[http] case object SecureRandomInstances extends (() ⇒ Random) {
    override def apply(): Random = new SecureRandom()
  }
}
