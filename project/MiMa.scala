/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._

import scala.io.Source
import scala.util.{Failure, Success, Try}

object MiMa extends AutoPlugin {

  override def requires = MimaPlugin
  override def trigger = allRequirements

  val mimaFiltersDirectory = settingKey[File]("Directory containing mima filters.")

  override val projectSettings = Seq(
    mimaFiltersDirectory := (sourceDirectory in Compile).value / "mima-filters",
    mimaBackwardIssueFilters ++= {
      val directory = mimaFiltersDirectory.value
      if (directory.exists) loadMimaIgnoredProblems(directory, ".backwards.excludes")
      else Map.empty
    },
    mimaPreviousArtifacts :=
      // manually maintained list of previous versions to make sure all incompatibilities are found
      // even if so far no files have been been created in this project's mima-filters directory
      Set("10.0.0",
          "10.0.1")
        .map((version: String) => organization.value %% name.value % version)
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
  def loadMimaIgnoredProblems(directory: File, fileExtension: String): Map[String, Seq[ProblemFilter]] = {
    val FilterAnyProblemPattern = """FilterAnyProblem\("([^"]+)"\)""".r
    val FilterAnyProblemStartingWithPattern = """FilterAnyProblemStartingWith\("([^"]+)"\)""".r
    val ExclusionPattern = """ProblemFilters\.exclude\[([^\]]+)\]\("([^"]+)"\)""".r

    def findFiles(): Seq[File] = directory.listFiles().filter(_.getName endsWith fileExtension)
    def parseFile(file: File): Either[Seq[Throwable], (String, Seq[ProblemFilter])] = {
      val version = file.getName.dropRight(fileExtension.size)

      def parseLine(text: String, line: Int): Try[ProblemFilter] =
        Try {
          text match {
            case ExclusionPattern(className, target) => ProblemFilters.exclude(className, target)
            case FilterAnyProblemPattern(target) => FilterAnyProblem(target)
            case FilterAnyProblemStartingWithPattern(target) => FilterAnyProblemStartingWith(target)
            case x => throw new RuntimeException(s"Couldn't parse '$x'")
          }
        }.transform(Success(_), ex => Failure(new ParsingException(file, line, ex)))

      val (excludes, failures) =
        Source.fromFile(file)
          .getLines()
          .zipWithIndex
          .filterNot { case (str, line) => str.trim.isEmpty || str.trim.startsWith("#") }
          .map((parseLine _).tupled)
          .partition(_.isSuccess)

      if (failures.isEmpty) Right(version -> excludes.map(_.get).toSeq)
      else Left(failures.map(_.failed.get).toSeq)
    }

    require(directory.exists(), s"Mima filter directory did not exist: ${directory.getAbsolutePath}")
    val (mappings, failures) =
      findFiles()
        .map(parseFile)
        .partition(_.isRight)

    if (failures.isEmpty) mappings.map(_.right.get).toMap
    else {
      // TODO: actually report errors using a logger, can only be done once keys have been converted to
      // taskKeys, see https://github.com/typesafehub/migration-manager/pull/157
      failures.flatMap(_.left.get).foreach(ex => println(ex.getMessage))

      throw new RuntimeException(s"Loading Mima filters failed with ${failures.size} failures.")
    }
  }

  case class ParsingException(file: File, line: Int, ex: Throwable) extends RuntimeException(s"Error while parsing $file, line $line: ${ex.getMessage}", ex)
}

