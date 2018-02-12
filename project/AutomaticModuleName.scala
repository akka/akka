/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka

import com.typesafe.sbt.osgi.{OsgiKeys, SbtOsgi}
import sbt.{Def, _}
import sbt.Keys._

/**
 * Helper to set Automatic-Module-Name in projects.
 *
 * !! DO NOT BE TEMPTED INTO AUTOMATICALLY DERIVING THE NAMES FROM PROJECT NAMES !!
 *
 * The names carry a lot of implications and DO NOT have to always align 1:1 with the group ids or package names,
 * though there should be of course a strong relationship between them.
 */
object AutomaticModuleName  {
  private val AutomaticModuleName = "Automatic-Module-Name"

  def settings(name: String): Seq[Def.Setting[_]] = Seq(
    packageOptions in (Compile, packageBin) += Package.ManifestAttributes(AutomaticModuleName → name),
    // explicitly pass it to the osgi plugin, otherwise it would lose that setting
    // This should be likely fixed in sbt-osgi, such that it does pick up package options as additional headers
    OsgiKeys.additionalHeaders := Map(AutomaticModuleName → name)
  )
}
