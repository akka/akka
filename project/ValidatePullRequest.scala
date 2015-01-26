/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.plugin.MimaKeys.reportBinaryIssues

object ValidatePullRequest extends AutoPlugin {

  val validatePullRequest = taskKey[Unit]("Additional tasks for pull request validation")

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    validatePullRequest := (),
    validatePullRequest <<= validatePullRequest.dependsOn(test in Test),

    // add reportBinaryIssues to validatePullRequest on minor version maintenance branch
    validatePullRequest <<= validatePullRequest.dependsOn(reportBinaryIssues)
  )
}
