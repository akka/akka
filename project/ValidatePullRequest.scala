/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import com.typesafe.tools.mima.plugin.MimaKeys.reportBinaryIssues
import net.virtualvoid.sbt.graph.IvyGraphMLDependencies
import net.virtualvoid.sbt.graph.IvyGraphMLDependencies.ModuleId
import org.kohsuke.github._
import sbt.Keys._
import sbt._

import scala.collection.immutable
import scala.util.matching.Regex

object ValidatePullRequest extends AutoPlugin {

  val ValidatePR = config("pr-validation") extend Test

  override lazy val projectConfigurations = Seq(ValidatePR)

  /*
    Assumptions:
      Env variables set "by Jenkins" are assumed to come from this plugin:
      https://wiki.jenkins-ci.org/display/JENKINS/GitHub+pull+request+builder+plugin
   */

  // settings
  val PullIdEnvVarName = "ghprbPullId" // Set by "GitHub pull request builder plugin"

  val TargetBranchEnvVarName = "PR_TARGET_BRANCH"
  val TargetBranchJenkinsEnvVarName = "ghprbTargetBranch"
  val targetBranch = settingKey[String]("Branch with which the PR changes should be diffed against")

  val SourceBranchEnvVarName = "PR_SOURCE_BRANCH"
  val SourcePullIdJenkinsEnvVarName = "ghprbPullId" // used to obtain branch name in form of "pullreq/17397"
  val sourceBranch = settingKey[String]("Branch containing the changes of this PR")

  // asking github comments if this PR should be PLS BUILD ALL
  val githubEnforcedBuildAll = taskKey[Boolean]("Checks via GitHub API if comments included the PLS BUILD ALL keyword")
  val buildAllKeyword = taskKey[Regex]("Magic phrase to be used to trigger building of the entire project instead of analysing dependencies")

  // determining touched dirs and projects
  val changedDirectories = taskKey[immutable.Set[String]]("List of touched modules in this PR branch")
  val projectIsAffectedByChanges = taskKey[Boolean]("True if this project is affected by the PR and should be rebuilt")

  // running validation
  val validatePullRequest = taskKey[Unit]("Additional tasks for pull request validation")

  override def trigger = allRequirements

  override lazy val projectSettings = inConfig(ValidatePR)(Defaults.testTasks) ++ Seq(
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "performance"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "long-running"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "timing"),

    targetBranch in ValidatePR := {
        sys.env.get(TargetBranchEnvVarName) orElse
        sys.env.get(TargetBranchJenkinsEnvVarName) getOrElse // Set by "GitHub pull request builder plugin"
        "master"
    },

    sourceBranch in ValidatePR := {
      sys.env.get(SourceBranchEnvVarName) orElse
      sys.env.get(SourcePullIdJenkinsEnvVarName).map("pullreq/" + _) getOrElse // Set by "GitHub pull request builder plugin"
      "HEAD"
    },

    changedDirectories in ValidatePR := {
      val log = streams.value.log

      val targetId = (targetBranch in ValidatePR).value
      val prId = (sourceBranch in ValidatePR).value

      // TODO could use jgit
      log.info(s"Comparing [$targetId] with [$prId] to determine changed modules in PR...")
      val gitOutput = "git diff %s..%s --name-only".format(targetId, prId).!!.split("\n")

      val moduleNames =
        gitOutput
          .map(l ⇒ l.trim.takeWhile(_ != '/'))
          .filter(_ startsWith "akka-")
          .toSet

      log.info("Detected changes in directories: " + moduleNames.mkString("[", ", ", "]"))
      moduleNames
    },

    buildAllKeyword in ValidatePR := """PLS BUILD ALL""".r,

    githubEnforcedBuildAll in ValidatePR := {
      sys.env.get(PullIdEnvVarName).map(_.toInt) match {
        case None => false // can't ask github if no issue ID given
        case Some(prId) =>
          val log = streams.value.log
          val buildAllMagicPhrase = (buildAllKeyword in ValidatePR).value
          log.info("Checking GitHub comments for PR validation options...")

          try {
            import scala.collection.JavaConverters._
            val gh = GitHubBuilder.fromEnvironment().withOAuthToken(GitHub.envTokenOrThrow).build()
            val comments = gh.getRepository("akka/akka").getIssue(prId).getComments.asScala

            comments exists { c =>
              val triggersBuildAll = buildAllMagicPhrase.findFirstIn(c.getBody).isDefined
              if (triggersBuildAll)
                log.info(s"GitHub PR comment [ ${c.getUrl} ] contains [$buildAllMagicPhrase], forcing BUILD ALL mode!")
              triggersBuildAll
            }
          } catch {
            case ex: Exception =>
              log.warn("Unable to reach GitHub! Exception was: " + ex.getMessage)
              false
          }
      }
    },

    projectIsAffectedByChanges in ValidatePR := (githubEnforcedBuildAll in ValidatePR).value || {
      val log = streams.value.log
      log.debug(s"Analysing project (for inclusion in PR validation): [${name.value}]")

      // if in any scope, any of the changed modules is within this projects dependencies, we must test it:
      val shouldBeBuilt = (changedDirectories in ValidatePR).value.exists { modifiedProject ⇒
        Set(Compile, Test, Runtime, Provided, Optional) exists { ivyScope: sbt.Configuration ⇒
          log.debug(s"Analysing [$ivyScope] scoped dependencies...")

          def moduleId(artifactName: String) = ModuleId("com.typesafe.akka", artifactName + "_" + scalaBinaryVersion.value, version.value)
          val modifiedModuleIds = Set(moduleId(modifiedProject), moduleId(modifiedProject + "-experimental"))

          def resolutionFilename(includeScalaVersion: Boolean) =
            s"%s-%s-%s.xml".format(
              organization.value,
              name.value + (if (includeScalaVersion) "_" + scalaBinaryVersion.value else ""),
              ivyScope.toString())
          def resolutionFile(includeScalaVersion: Boolean) =
            target.value / "resolution-cache" / "reports" / resolutionFilename(includeScalaVersion)

          val ivyReportFile = {
            val f1 = resolutionFile(includeScalaVersion = true)
            val f2 = resolutionFile(includeScalaVersion = false)
            if (f1.exists()) f1 else f2
          }

          val deps = IvyGraphMLDependencies.graph(ivyReportFile.getAbsolutePath)
          deps.nodes.foreach { m ⇒ log.debug(" -> " + m.id) }

          // if this project depends on a modified module, we must test it
          deps.nodes.exists { m =>
            val depends = modifiedModuleIds exists { _.name == m.id.name } // match just by name, we'd rather include too much than too little
            if (depends) log.info(s"Project [${name.value}] must be verified, because depends on [${modifiedModuleIds.find(_ == m.id).get}]")
            depends
          }
        }
      }

      shouldBeBuilt
    },

    validatePullRequest := Def.taskDyn {
      val log = streams.value.log
      val theVoid = Def.task { () } // when you stare into the void, the void stares back at you

      if ((projectIsAffectedByChanges in ValidatePR).value) {
        log.info(s"Changes in PR are affecting project [${name.value}] - proceeding with pr-validation:test")
        theVoid.dependsOn(test in ValidatePR)
      } else {
        log.info(s"Skipping validation of [${name.value}], as PR does NOT affect this project...")
        theVoid
      }
    }.value,

    // add reportBinaryIssues to validatePullRequest on minor version maintenance branch
    validatePullRequest <<= validatePullRequest.dependsOn(reportBinaryIssues)
  )
}
