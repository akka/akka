/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.plugin.MimaKeys.binaryIssueFilters
import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings

object MiMa extends AutoPlugin {

  override def trigger = allRequirements

  override val projectSettings = mimaDefaultSettings ++ Seq(
    previousArtifact := None,
    binaryIssueFilters ++= mimaIgnoredProblems
  )

  val mimaIgnoredProblems = {
    Seq(
      // add filters here, see release-2.3 branch
     )
  }
}
