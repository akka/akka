/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._

object MiMa extends AutoPlugin {

  override def requires = MimaPlugin
  override def trigger = allRequirements

  //Exclude these non-existent versions when checking compatibility with previous versions
  private val ignoredModules = Map(
    "akka-http-caching" -> Set("10.0.0", "10.0.1", "10.0.2", "10.0.3", "10.0.4", "10.0.5", "10.0.6", "10.0.7", "10.0.8", "10.0.9", "10.0.10")
  )

  override val projectSettings = Seq(
    mimaPreviousArtifacts :=
      // manually maintained list of previous versions to make sure all incompatibilities are found
      // even if so far no files have been been created in this project's mima-filters directory
      Set("10.0.0",
          "10.0.1",
          "10.0.2",
          "10.0.3",
          "10.0.4",
          "10.0.5",
          "10.0.6",
          "10.0.7",
          "10.0.8",
          "10.0.9",
          "10.0.10",
          "10.0.11"
      )
        .collect { case version if !ignoredModules.get(name.value).exists(_.contains(version)) =>
          organization.value %% name.value % version
        }
  )
}
