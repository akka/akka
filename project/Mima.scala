/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._

import scala.io.Source

object MiMa extends AutoPlugin {

  override def requires = MimaPlugin
  override def trigger = allRequirements

  override val projectSettings = Seq(
    mimaBackwardIssueFilters ++= loadMimaIgnoredProblems(file("project/mima-filters")),
    mimaPreviousArtifacts := mimaBackwardIssueFilters.value.keys.map((version: String) => organization.value %% name.value % version).toSet
  )

  case class FilterAnyProblem(name: String) extends com.typesafe.tools.mima.core.ProblemFilter {
    import com.typesafe.tools.mima.core._
    override def apply(p: Problem): Boolean = p match {
      case t: TemplateProblem => t.ref.fullName != name && t.ref.fullName != (name + '$')
      case m: MemberProblem => m.ref.owner.fullName != name && m.ref.owner.fullName != (name + '$')
    }
  }

  case class FilterAnyProblemStartingWith(start: String) extends com.typesafe.tools.mima.core.ProblemFilter {
    import com.typesafe.tools.mima.core._
    override def apply(p: Problem): Boolean = p match {
      case t: TemplateProblem => !t.ref.fullName.startsWith(start)
      case m: MemberProblem => !m.ref.owner.fullName.startsWith(start)
    }
  }

  import com.typesafe.tools.mima.core._
  def loadMimaIgnoredProblems(directory: File): Map[String, Seq[ProblemFilter]] = {
    val fileExtension = ".excludes"
    val FilterAnyProblemPattern = """FilterAnyProblem\("([^"]+)"\)""".r
    val FilterAnyProblemStartingWithPattern = """FilterAnyProblemStartingWith\("([^"]+)"\)""".r
    val ExclusionPattern = """ProblemFilters\.exclude\[([^\]]+)\]\("([^"]+)"\)""".r

    def findFiles(): Seq[File] = directory.listFiles().filter(_.getName endsWith fileExtension)
    def parseFile(file: File): (String, Seq[ProblemFilter]) = {
      val version = file.getName.dropRight(fileExtension.size)

      val excludes = Source.fromFile(file).getLines().filterNot(str => str.trim.isEmpty || str.trim.startsWith("#")).flatMap {
        case ExclusionPattern(className, target) => Some(ProblemFilters.exclude(className, target))
        case FilterAnyProblemPattern(target) => Some(FilterAnyProblem(target))
        case FilterAnyProblemStartingWithPattern(target) => Some(FilterAnyProblemStartingWith(target))
        case x =>
          println(s"Couldn't parse '$x'")
          None
      }

      (version, excludes.toSeq)
    }

    require(directory.exists(), s"Mima filter directory did not exist: ${directory.getAbsolutePath}")
    findFiles().map(parseFile).toMap
  }
}
