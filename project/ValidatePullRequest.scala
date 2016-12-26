/**
  * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
  */
import akka.GitHub
import com.typesafe.tools.mima.plugin.MimaKeys.mimaReportBinaryIssues
import com.typesafe.tools.mima.plugin.MimaPlugin
import net.virtualvoid.sbt.graph.backend.SbtUpdateReport
import net.virtualvoid.sbt.graph.ModuleGraph
import org.kohsuke.github.{GHIssueComment, GitHubBuilder}
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
      l.info(s"GitHub PR comment [${c.getUrl}] contains [$phrase], forcing BUILD ALL mode!")
  }

  val ValidatePR = config("pr-validation") extend Test

  override lazy val projectConfigurations = Seq(ValidatePR)

  /**
    * Assumptions:
    * Env variables set "by Jenkins" are assumed to come from this plugin:
    * https://wiki.jenkins-ci.org/display/JENKINS/GitHub+pull+request+builder+plugin
    */

  // settings
  val PullIdEnvVarName = "ghprbPullId" // Set by "GitHub pull request builder plugin"

  val TargetBranchEnvVarName = "PR_TARGET_BRANCH"
  val TargetBranchJenkinsEnvVarName = "ghprbTargetBranch"
  val SourceBranchEnvVarName = "PR_SOURCE_BRANCH"
  val SourcePullIdJenkinsEnvVarName = "ghprbPullId" // used to obtain branch name in form of "pullreq/17397"

  val sourceBranch = settingKey[String]("Branch containing the changes of this PR")
  val targetBranch = settingKey[String]("Target branch of this PR, defaults to `master`")

  // asking github comments if this PR should be PLS BUILD ALL
  val gitHubEnforcedBuildAll = taskKey[Option[BuildMode]]("Checks via GitHub API if comments included the PLS BUILD ALL keyword")
  val buildAllKeyword = taskKey[Regex]("Magic phrase to be used to trigger building of the entire project instead of analysing dependencies")

  // determining touched dirs and projects
  val changedDirectories = taskKey[immutable.Set[String]]("List of touched modules in this PR branch")
  val validatePRprojectBuildMode = taskKey[BuildMode]("Determines what will run when this project is affected by the PR and should be rebuilt")

  // running validation
  val validatePullRequest = taskKey[Unit]("Validate pull request")
  val additionalTasks = taskKey[Seq[TaskKey[_]]]("Additional tasks for pull request validation")

  def changedDirectoryIsDependency(changedDirs: Set[String], name: String, graphsToTest: Seq[(Configuration, ModuleGraph)])(log: Logger): Boolean = {
    val dirsOrExperimental = changedDirs.flatMap(dir => Set(dir, s"$dir-experimental"))
    graphsToTest exists { case (ivyScope, deps) =>
      log.debug(s"Analysing [$ivyScope] scoped dependencies...")

      deps.nodes.foreach { m ⇒ log.debug(" -> " + m.id) }

      // if this project depends on a modified module, we must test it
      deps.nodes.exists { m =>
        // match just by name, we'd rather include too much than too little
        val dependsOnModule = dirsOrExperimental.find(m.id.name contains _)
        val depends = dependsOnModule.isDefined
        if (depends) log.info(s"Project [$name] must be verified, because depends on [${dependsOnModule.get}]")
        depends
      }
    }
  }

  def localTargetBranch: Option[String] = sys.env.get("PR_TARGET_BRANCH")
  def jenkinsTargetBranch: Option[String] = sys.env.get("ghprbTargetBranch")
  def runningOnJenkins: Boolean = jenkinsTargetBranch.isDefined
  def runningLocally: Boolean = !runningOnJenkins

  override lazy val buildSettings = Seq(
    sourceBranch in Global in ValidatePR := {
      sys.env.get(SourceBranchEnvVarName) orElse
        sys.env.get(SourcePullIdJenkinsEnvVarName).map("pullreq/" + _) getOrElse // Set by "GitHub pull request builder plugin"
        "HEAD"
    },

    targetBranch in Global in ValidatePR := {
      (localTargetBranch, jenkinsTargetBranch) match {
        case (Some(local), _)     => local // local override
        case (None, Some(branch)) => s"origin/$branch" // usually would be "master" or "release-2.3" etc
        case (None, None)         => "origin/master" // defaulting to diffing with "master"
      }
    },

    buildAllKeyword in Global in ValidatePR := """PLS BUILD ALL""".r,

    gitHubEnforcedBuildAll in Global in ValidatePR := {
      sys.env.get(PullIdEnvVarName).map(_.toInt) flatMap { prId =>
        val log = streams.value.log
        val buildAllMagicPhrase = (buildAllKeyword in ValidatePR).value
        log.info("Checking GitHub comments for PR validation options...")

        try {
          import scala.collection.JavaConverters._
          val gh = GitHubBuilder.fromEnvironment().withOAuthToken(GitHub.envTokenOrThrow).build()
          val comments = gh.getRepository("akka/akka-http").getIssue(prId).getComments.asScala

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

    changedDirectories in Global in ValidatePR := {
      val log = streams.value.log
      val prId = (sourceBranch in ValidatePR).value
      val target = (targetBranch in ValidatePR).value

      log.info(s"Diffing [$prId] to determine changed modules in PR...")
      val diffOutput = s"git diff $target --name-only".!!.split("\n")
      val diffedModuleNames =
        diffOutput
          .map(l => l.trim)
          .filter(l =>
            l.startsWith("akka-") ||
              l.startsWith("docs") ||
              (l.startsWith("project") && l != "project/MiMa.scala")
          )
          .map(l ⇒ l.takeWhile(_ != '/'))
          .toSet

      val dirtyModuleNames: Set[String] =
        if (runningOnJenkins) Set.empty
        else {
          val statusOutput = s"git status --short".!!.split("\n")
          val dirtyDirectories = statusOutput
            .map(l ⇒ l.trim.dropWhile(_ != ' ').drop(1))
            .map(_.takeWhile(_ != '/'))
            .filter(dir => dir.startsWith("akka-") || dir.startsWith("docs") || dir == "project")
            .toSet
          log.info("Detected uncomitted changes in directories (including in dependency analysis): " + dirtyDirectories.mkString("[", ",", "]"))
          dirtyDirectories
        }

      val allModuleNames = dirtyModuleNames ++ diffedModuleNames
      log.info("Detected changes in directories: " + allModuleNames.mkString("[", ", ", "]"))
      allModuleNames
    }
  )

  override lazy val projectSettings = inConfig(ValidatePR)(Defaults.testTasks) ++ Seq(
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "performance"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "long-running"),
    testOptions in ValidatePR += Tests.Argument(TestFrameworks.ScalaTest, "-l", "timing"),

    validatePRprojectBuildMode in ValidatePR := {
      val log = streams.value.log
      log.debug(s"Analysing project (for inclusion in PR validation): [${name.value}]")
      val changedDirs = (changedDirectories in ValidatePR).value
      val githubCommandEnforcedBuildAll = (gitHubEnforcedBuildAll in ValidatePR).value

      val thisProjectId = CrossVersion(scalaVersion.value, scalaBinaryVersion.value)(projectID.value)

      def graphFor(updateReport: UpdateReport, config: Configuration): (Configuration, ModuleGraph) =
        config -> SbtUpdateReport.fromConfigurationReport(updateReport.configuration(config.name).get, thisProjectId)

      def isDependency: Boolean =
        changedDirectoryIsDependency(
          changedDirs,
          name.value,
          Seq(
            graphFor((update in Compile).value, Compile),
            graphFor((update in Test).value, Test),
            graphFor((update in Runtime).value, Runtime),
            graphFor((update in Provided).value, Provided),
            graphFor((update in Optional).value, Optional)))(log)

      if (githubCommandEnforcedBuildAll.isDefined)
        githubCommandEnforcedBuildAll.get
      else if (changedDirs contains "project")
        BuildProjectChangedQuick
      else if (isDependency)
        BuildQuick
      else
        BuildSkip
    },

    additionalTasks in ValidatePR := Seq.empty,

    validatePullRequest := Def.taskDyn {
      val log = streams.value.log
      val buildMode = (validatePRprojectBuildMode in ValidatePR).value

      buildMode.log(name.value, log)

      val validationTasks = buildMode.task.toSeq ++ (buildMode match {
        case BuildSkip => Seq.empty // do not run the additional task if project is skipped during pr validation
        case _ => (additionalTasks in ValidatePR).value
      })

      // Create a task for every validation task key and
      // then zip all of the tasks together discarding outputs.
      // Task failures are propagated as normal.
      val zero: Def.Initialize[Seq[Task[Any]]] = Def.setting { Seq(task(()))}
      validationTasks.map(taskKey => Def.task { taskKey.value } ).foldLeft(zero) { (acc, current) =>
        acc.zipWith(current) { case (taskSeq, task) =>
          taskSeq :+ task.asInstanceOf[Task[Any]]
        }
      } apply { tasks: Seq[Task[Any]] =>
        tasks.join map { seq => () /* Ignore the sequence of unit returned */ }
      }
    }.value
  )
}

/**
  * This auto plugin adds MiMa binary issue reporting to validatePullRequest task,
  * when a project has MimaPlugin auto plugin enabled.
  */
object MimaWithPrValidation extends AutoPlugin {
  import ValidatePullRequest._

  override def trigger = allRequirements
  override def requires = ValidatePullRequest && MimaPlugin
  override lazy val projectSettings = Seq(
    additionalTasks in ValidatePR += mimaReportBinaryIssues
  )
}

/**
  * This auto plugin adds UniDoc unification to validatePullRequest task.
  */
object UniDocWithPrValidation extends AutoPlugin {
  import ValidatePullRequest._

  override def trigger = noTrigger
  override lazy val projectSettings = Seq(
    additionalTasks in ValidatePR += unidoc in Compile
  )
}