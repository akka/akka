/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import akka.annotation.InternalApi

final class UnsupportedAkkaVersion private[akka] (msg: String) extends RuntimeException(msg)

object AkkaVersion {
  /**
   * Check that the version of Akka is a specific patch version or higher and throw an [[UnsupportedAkkaVersion]]
   * exception if the version requirement is not fulfilled.
   *
   * For example: `require("my-library", "2.5.4")` would fail if used with Akka 2.4.19 and 2.5.3, but succeed with 2.5.4
   * and 2.6.1
   *
   * @param libraryName The name of the library or component requiring the Akka version, used in the error message.
   * @param requiredVersion Minimal version that this library works with
   */
  def require(libraryName: String, requiredVersion: String): Unit = {
    require(libraryName, requiredVersion, Version.current)
  }

  /**
   * Internal API:
   */
  @InternalApi
  private[akka] def require(libraryName: String, requiredVersion: String, currentVersion: String): Unit = {
    val VersionPattern = """(\d+)\.(\d+)\.(\d+)(-(?:M|RC)\d+)?""".r
    currentVersion match {
      case VersionPattern(currentMajorStr, currentMinorStr, currentPatchStr, mOrRc) ⇒
        requiredVersion match {
          case requiredVersion @ VersionPattern(requiredMajorStr, requiredMinorStr, requiredPatchStr, _) ⇒
            // a M or RC is basically in-between versions, so offset
            val currentPatch =
              if (mOrRc ne null) currentPatchStr.toInt - 1
              else currentPatchStr.toInt
            if (requiredMajorStr.toInt != currentMajorStr.toInt ||
              requiredMinorStr.toInt > currentMinorStr.toInt ||
              (requiredMinorStr == currentMinorStr && requiredPatchStr.toInt > currentPatch))
              throw new UnsupportedAkkaVersion(s"Current version of Akka is [$currentVersion], but $libraryName requires version [$requiredVersion]")
          case _ ⇒ throw new IllegalArgumentException(s"Required version string is invalid: [$requiredVersion]")
        }

      case _ ⇒ // SNAPSHOT or unknown - you're on your own
    }
  }

}
