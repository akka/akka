/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model

import akka.util.Unsafe

/**
 * Provides workarounds around JavaDSL initialisation edge cases.
 *
 * FIXME: Exists only to fix JavaDSL init problem: #19162. Remove in Akka 3.x when "ALL" static fields moved
 */
private[akka] object JavaInitialization {

  private[this] val u = Unsafe.instance

  def initializeStaticFieldWith[T](value: T, f: java.lang.reflect.Field): Unit =
    u.putObject(u.staticFieldBase(f), u.staticFieldOffset(f), value)
}
