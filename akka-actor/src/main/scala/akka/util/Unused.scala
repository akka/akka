/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.annotation.InternalApi

/**
 * Marker for explicit or implicit parameter known to be unused, yet
 * still necessary from a binary compatibility perspective
 * or other reason. Useful in combination with
 * `-Ywarn-unused:explicits,implicits` compiler options.
 *
 * INTERNAL API
 */
@InternalApi private[akka] class unused extends deprecated("unused", "")
