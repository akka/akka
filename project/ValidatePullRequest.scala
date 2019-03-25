/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import com.hpe.sbt.ValidatePullRequest
import com.hpe.sbt.ValidatePullRequest.PathGlobFilter
import com.lightbend.paradox.sbt.ParadoxPlugin
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport.paradox
import com.typesafe.tools.mima.plugin.MimaKeys.mimaReportBinaryIssues
import com.typesafe.tools.mima.plugin.MimaPlugin
import sbtunidoc.BaseUnidocPlugin.autoImport.unidoc
import sbt.Keys._
import sbt._

object AkkaValidatePullRequest extends AutoPlugin {

  import ValidatePullRequest.autoImport._

  override def trigger = allRequirements
  override def requires = ValidatePullRequest

  val ValidatePR = config("pr-validation") extend Test

  override lazy val projectConfigurations = Seq(ValidatePR)

  val additionalTasks = settingKey[Seq[TaskKey[_]]]("Additional tasks for pull request validation")

  override lazy val globalSettings = Seq(
    credentials ++= {
      // todo this should probably be supplied properly
      GitHub.envTokenOrThrow.map { token =>
        Credentials("GitHub API", "api.github.com", "", token)
      }
    },
    additionalTasks := Seq.empty
  )

  override lazy val buildSettings = Seq(
    validatePullRequest / includeFilter := PathGlobFilter("akka-*/**"),
    validatePullRequestBuildAll / excludeFilter := PathGlobFilter("project/MiMa.scala"),
    prValidatorGithubRepository := Some("akka/akka")
  )

  override lazy val projectSettings = inConfig(ValidatePR)(Defaults.testTasks) ++ Seq(
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "performance"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "long-running"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "timing"),

    // make it fork just like regular test running
    fork in ValidatePR := (fork in Test).value,
    testGrouping in ValidatePR := (testGrouping in Test).value,
    javaOptions in ValidatePR := (javaOptions in Test).value,

    prValidatorTasks := Seq(test in ValidatePR) ++ additionalTasks.value,
    prValidatorEnforcedBuildAllTasks := Seq(test in Test) ++ additionalTasks.value
  )
}

/**
* This autoplugin adds Multi Jvm tests to validatePullRequest task.
* It is needed, because ValidatePullRequest autoplugin does not depend on MultiNode and
* therefore test:executeTests is not yet modified to include multi-jvm tests when ValidatePullRequest
* build strategy is being determined.
*
* Making ValidatePullRequest depend on MultiNode is impossible, as then ValidatePullRequest
* autoplugin would trigger only on projects which have both of these plugins enabled.
*/
object MultiNodeWithPrValidation extends AutoPlugin {
  import AkkaValidatePullRequest._
  import com.typesafe.sbt.MultiJvmPlugin.MultiJvmKeys.MultiJvm

  override def trigger = allRequirements
  override def requires = AkkaValidatePullRequest && MultiNode
  override lazy val projectSettings =
    if (MultiNode.multiNodeTestInTest) Seq(additionalTasks += MultiNode.multiTest)
    else Seq.empty
}

/**
* This autoplugin adds MiMa binary issue reporting to validatePullRequest task,
* when a project has MimaPlugin autoplugin enabled.
*/
object MimaWithPrValidation extends AutoPlugin {
  import AkkaValidatePullRequest._

  override def trigger = allRequirements
  override def requires = AkkaValidatePullRequest && MimaPlugin
  override lazy val projectSettings = Seq(
    additionalTasks += mimaReportBinaryIssues
  )
}

/**
  * This autoplugin adds Paradox doc generation to validatePullRequest task,
  * when a project has ParadoxPlugin autoplugin enabled.
  */
object ParadoxWithPrValidation extends AutoPlugin {
  import AkkaValidatePullRequest._

  override def trigger = allRequirements
  override def requires = AkkaValidatePullRequest && ParadoxPlugin
  override lazy val projectSettings = Seq(
    additionalTasks += paradox in Compile
  )
}

object UnidocWithPrValidation extends AutoPlugin {
  import AkkaValidatePullRequest._

  override def trigger = noTrigger
  override lazy val projectSettings = Seq(
    additionalTasks += unidoc in Compile
  )
}
