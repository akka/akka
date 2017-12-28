/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import akka.annotation.InternalApi


final class UnsupportedAkkaVersion private[akka](msg: String) extends RuntimeException(msg)

object AkkaVersion {
  /**
   * Check that the version of Akka is a specific patch version or higher, potentially for multiple
   * minor version, for example require("2.4.19", "2.5.6"). If the version requirement is not fulfilled
   * an exception is thrown.
   *
   * @param libraryName The name of the library or component requiring the Akka version, used in the error message.
   */
  def require(libraryName: String, requiredVersions: String*): Unit = {
    require(libraryName, requiredVersions.toSet, Version.current)
  }

  /**
   * Internal API:
   */
  @InternalApi
  private[akka] def require(libraryName: String, requiredVersions: Set[String], currentVersion: String): Unit = {
    val Array(majorStr, minorStr, patchStr) = currentVersion.split(Array('.', '-'))

    // we can only know that it matches if it's not a SNAPSHOT or a timestamp as patch version
    if (patchStr.matches("\\d{1,3}")) {
      val minorVersionStr = s"$majorStr.$minorStr"

      requiredVersions.find(_.startsWith(minorVersionStr)) match {
        case Some(requiredVersion) =>
          val Array(_, _, requiredPatchStr) = requiredVersion.split('.')
          val patchNr = patchStr.toInt
          try {
            val requiredPatchNr = requiredPatchStr.toInt
            if (requiredPatchNr > patchNr)
              throw new UnsupportedAkkaVersion(s"Current version of Akka is $currentVersion, but '$libraryName' requires version $requiredVersion")

          } catch {
            case ex: NumberFormatException =>
              throw new IllegalArgumentException(s"Illegal version requirement string '$requiredVersion', must be x.y.z with numbers")
          }
        case None =>
          // minor version not supported
          throw new UnsupportedAkkaVersion(s"Current version of Akka is $currentVersion, but '$libraryName' requires $requiredVersions")
      }
    } else {
      throw new UnsupportedAkkaVersion(s"Current version of Akka is $currentVersion (likely a snapshot), but '$libraryName' requires $requiredVersions")
    }
  }

}
