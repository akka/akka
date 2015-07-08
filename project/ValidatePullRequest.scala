/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import com.typesafe.tools.mima.plugin.MimaKeys.reportBinaryIssues
import net.virtualvoid.sbt.graph.IvyGraphMLDependencies
import net.virtualvoid.sbt.graph.IvyGraphMLDependencies.ModuleId
import org.kohsuke.github._
import sbtunidoc.Plugin.UnidocKeys.unidoc
import sbt.Keys._
import sbt._

import scala.collection.immutable
import scala.util.matching.Regex

object ValidatePullRequest extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  sealed trait BuildMode {
    def task: Option[TaskKey[_]]
    def log(projectName: String, l: Logger): Unit
  }
  case object BuildSkip extends BuildMode {
    override def task = None
    def log(projectName: String, l: Logger) =
      l.info(s"Skipping validation of [$projectName], as PR does NOT affect this project...")
  }
  case object BuildQuick extends BuildMode {
    override def task = Some(test in ValidatePR)
    def log(projectName: String, l: Logger) =
      l.info(s"Building [$projectName] in quick mode, as it's dependencies were affected by PR.")
  }
  case object BuildProjectChangedQuick extends BuildMode {
    override def task = Some(test in ValidatePR)
    def log(projectName: String, l: Logger) =
      l.info(s"Building [$projectName] as the root `project/` directory was affected by this PR.")
  }
  final case class BuildCommentForcedAll(phrase: String, c: GHIssueComment) extends BuildMode {
    override def task = Some(test in Test)
    def log(projectName: String, l: Logger) =
      l.info(s"GitHub PR comment [ ${c.getUrl} ] contains [$phrase], forcing BUILD ALL mode!")
  }

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

  val SourceBranchEnvVarName = "PR_SOURCE_BRANCH"
  val SourcePullIdJenkinsEnvVarName = "ghprbPullId" // used to obtain branch name in form of "pullreq/17397"
  val sourceBranch = settingKey[String]("Branch containing the changes of this PR")

  // asking github comments if this PR should be PLS BUILD ALL
  val githubEnforcedBuildAll = taskKey[Option[BuildMode]]("Checks via GitHub API if comments included the PLS BUILD ALL keyword")
  val buildAllKeyword = taskKey[Regex]("Magic phrase to be used to trigger building of the entire project instead of analysing dependencies")

  // determining touched dirs and projects
  val changedDirectories = taskKey[immutable.Set[String]]("List of touched modules in this PR branch")
  val projectBuildMode = taskKey[BuildMode]("Determines what will run when this project is affected by the PR and should be rebuilt")

  // running validation
  val validatePullRequest = taskKey[Unit]("Validate pull request")
  val additionalTasks = taskKey[Seq[TaskKey[_]]]("Additional tasks for pull request validation")

  def changedDirectoryIsDependency(changedDirs: Set[String],
                                   target: File,
                                   scalaBinaryVersion: String, version: String,
                                   organization: String, name: String)(log: Logger): Boolean = {
    changedDirs.exists { modifiedProject ⇒
      Set(Compile, Test, Runtime, Provided, Optional) exists { ivyScope: Configuration ⇒
        log.debug(s"Analysing [$ivyScope] scoped dependencies...")

        def moduleId(artifactName: String) = ModuleId("com.typesafe.akka", artifactName + "_" + scalaBinaryVersion, version)
        val modifiedModuleIds = Set(moduleId(modifiedProject), moduleId(modifiedProject + "-experimental"))

        def resolutionFilename(includeScalaVersion: Boolean) =
          s"%s-%s-%s.xml".format(
            organization,
            name + (if (includeScalaVersion) "_" + scalaBinaryVersion else ""),
            ivyScope.toString())
        def resolutionFile(includeScalaVersion: Boolean) =
          target / "resolution-cache" / "reports" / resolutionFilename(includeScalaVersion)

        val ivyReportFile = {
          val f1 = resolutionFile(includeScalaVersion = true)
          val f2 = resolutionFile(includeScalaVersion = false)
          if (f1.exists()) f1 else f2
        }

        val deps = IvyGraphMLDependencies.graph(ivyReportFile.getAbsolutePath)
        deps.nodes.foreach { m ⇒ log.debug(" -> " + m.id) }

        // if this project depends on a modified module, we must test it
        deps.nodes.exists { m =>
          // match just by name, we'd rather include too much than too little
          val dependsOnModule = modifiedModuleIds find { _.name == m.id.name }
          val depends = dependsOnModule.isDefined
          if (depends) log.info(s"Project [$name] must be verified, because depends on [$dependsOnModule]")
          depends
        }
      }
    }
  }


  override lazy val projectSettings = inConfig(ValidatePR)(Defaults.testTasks) ++ Seq(
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "performance"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "long-running"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "timing"),

    sourceBranch in ValidatePR := {
      sys.env.get(SourceBranchEnvVarName) orElse
      sys.env.get(SourcePullIdJenkinsEnvVarName).map("pullreq/" + _) getOrElse // Set by "GitHub pull request builder plugin"
      "HEAD"
    },

    changedDirectories in ValidatePR := {
      val log = streams.value.log

      val prId = (sourceBranch in ValidatePR).value

      // TODO could use jgit
      log.info(s"Diffing [$prId] to determine changed modules in PR...")
      val gitOutput = "git diff HEAD^ --name-only".!!.split("\n")

      val moduleNames =
        gitOutput
          .map(l ⇒ l.trim.takeWhile(_ != '/'))
          .filter(dir => dir.startsWith("akka-") || dir == "project")
          .toSet

      log.info("Detected changes in directories: " + moduleNames.mkString("[", ", ", "]"))
      moduleNames
    },

    buildAllKeyword in ValidatePR := """PLS BUILD ALL""".r,

    githubEnforcedBuildAll in ValidatePR := {
      sys.env.get(PullIdEnvVarName).map(_.toInt) flatMap { prId =>
        val log = streams.value.log
        val buildAllMagicPhrase = (buildAllKeyword in ValidatePR).value
        log.info("Checking GitHub comments for PR validation options...")

        try {
          import scala.collection.JavaConverters._
          val gh = GitHubBuilder.fromEnvironment().withOAuthToken(GitHub.envTokenOrThrow).build()
          val comments = gh.getRepository("akka/akka").getIssue(prId).getComments.asScala

          def triggersBuildAll(c: GHIssueComment): Boolean = buildAllMagicPhrase.findFirstIn(c.getBody).isDefined
          comments collectFirst { case c if triggersBuildAll(c) =>
            BuildCommentForcedAll(buildAllMagicPhrase.toString(), c)
          }
        } catch {
          case ex: Exception =>
            log.warn("Unable to reach GitHub! Exception was: " + ex.getMessage)
            None
        }
      }
    },

    projectBuildMode in ValidatePR := {
      val log = streams.value.log
      log.debug(s"Analysing project (for inclusion in PR validation): [${name.value}]")
      val changedDirs = (changedDirectories in ValidatePR).value
      val githubCommandEnforcedBuildAll = (githubEnforcedBuildAll in ValidatePR).value

      if (githubCommandEnforcedBuildAll.isDefined)
        githubCommandEnforcedBuildAll.get
      else if (changedDirs contains "project")
        BuildProjectChangedQuick
      else if (changedDirectoryIsDependency(changedDirs, target.value, scalaBinaryVersion.value, version.value,
                                            organization.value, name.value)(log))
        BuildQuick
      else
        BuildSkip
    },

    additionalTasks in ValidatePR := Seq.empty,

    validatePullRequest := Def.taskDyn {
      val log = streams.value.log
      val buildMode = (projectBuildMode in ValidatePR).value

      buildMode.log(name.value, log)

      val validationTasks = buildMode.task.toSeq ++ (buildMode match {
        case BuildSkip => Seq.empty // do not run the additional task if project is skipped during pr validation
        case _ => (additionalTasks in ValidatePR).value
      })

      // Create a task for every validation task key and
      // then zip all of the tasks together discarding outputs.
      // Task failures are propagated as normal.
      val zero: Def.Initialize[Seq[Task[Any]]] = Def.setting { Seq(task())}
      validationTasks.map(taskKey => Def.task { taskKey.value } ).foldLeft(zero) { (acc, current) =>
        acc.zipWith(current) { case (taskSeq, task) =>
          taskSeq :+ task.asInstanceOf[Task[Any]]
        }
      } apply { tasks: Seq[Task[Any]] =>
        tasks.join map { seq => () /* Ignore the sequence of unit returned */ }
      }
    }.value,

    // add reportBinaryIssues to validatePullRequest on minor version maintenance branch
    validatePullRequest <<= validatePullRequest.dependsOn(reportBinaryIssues)
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
  import ValidatePullRequest._

  override def trigger = allRequirements
  override def requires = ValidatePullRequest && MultiNode
  override lazy val projectSettings = Seq(
    additionalTasks in ValidatePR += MultiNode.multiTest
  )
}

object UnidocWithPrValidation extends AutoPlugin {
  import ValidatePullRequest._

  override def trigger = noTrigger
  override lazy val projectSettings = Seq(
    additionalTasks in ValidatePR += unidoc in Compile
  )
}
