/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import org.scalatest.{ Ignore, Tag }
import scala.util.Properties

object IgnoreForScala212
    extends Tag(if (Properties.versionNumberString.startsWith("2.12")) classOf[Ignore].getName else "")
