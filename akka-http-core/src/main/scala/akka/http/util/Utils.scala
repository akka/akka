/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

object Utils {
  def humanReadableByteCount(bytes: Long, si: Boolean): String = {
    val unit = if (si) 1000 else 1024
    if (bytes >= unit) {
      val exp = (math.log(bytes) / math.log(unit)).toInt
      val pre = if (si) "kMGTPE".charAt(exp - 1).toString else "KMGTPE".charAt(exp - 1).toString + 'i'
      "%.1f %sB" format (bytes / math.pow(unit, exp), pre)
    } else bytes.toString + "  B"
  }
}
