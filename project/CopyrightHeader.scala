/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.HeaderPattern.commentBetween
import de.heikoseeberger.sbtheader.{CommentBlockCreator, CommentCreator, CommentStyle, HeaderPlugin}

trait CopyrightHeader extends AutoPlugin {
  import HeaderPlugin.autoImport._

  override def requires = HeaderPlugin
  override def trigger = allRequirements

  override def projectSettings = Def.settings(
    Seq(Compile, Test).flatMap { config =>
      inConfig(config)(
        Seq(
          headerLicense := Some(HeaderLicense.Custom(headerFor(CurrentYear))),
          headerMappings := headerMappings.value ++ Map(
            HeaderFileType.scala       -> lightbendCommentStyle,
            HeaderFileType.java        -> lightbendCommentStyle,
            HeaderFileType("template") -> lightbendCommentStyle
          ),
          compile := compile.dependsOn(HeaderPlugin.autoImport.headerCreate).value
        )
      )
    }
  )

  val CurrentYear = java.time.Year.now.getValue.toString
  val CopyrightPattern = "Copyright \\([Cc]\\) (\\d{4}(-\\d{4})?) (Lightbend|Typesafe) Inc. <.*>".r
  val CopyrightHeaderPattern = s"(?s).*${CopyrightPattern}.*\n*".r

  def headerFor(year: String): String =
    s"Copyright (C) $year Lightbend Inc. <https://www.lightbend.com>"

  val lightbendCommentCreator = new CommentCreator() {

    import HeaderCommentStyle.cStyleBlockComment.commentCreator

    def updateLightbendHeader(header: String): String = header match {
      case CopyrightHeaderPattern(years, null, _) =>
        if (years != CurrentYear)
          CopyrightPattern.replaceFirstIn(header, headerFor(years + "-" + CurrentYear))
        else
          CopyrightPattern.replaceFirstIn(header, headerFor(years))
      case CopyrightHeaderPattern(years, endYears, _) =>
        CopyrightPattern.replaceFirstIn(header, headerFor(years.replace(endYears, "-" + CurrentYear)))
      case _ =>
        header
    }

    override def apply(text: String, existingText: Option[String]): String = {
      existingText
        .map(updateLightbendHeader)
        .getOrElse(commentCreator(text, existingText))
        .trim
    }
  }

  val lightbendCommentStyle = CommentStyle.cStyleBlockComment
    .copy(commentCreator = lightbendCommentCreator)
}

object CopyrightHeaderWithPr extends AutoPlugin with CopyrightHeader {

  import HeaderPlugin.autoImport._

  import ValidatePullRequest.{ additionalTasks, ValidatePR }

  override def projectSettings = super.projectSettings ++ Def.settings(
    additionalTasks in ValidatePR += headerCheck in Compile,
    additionalTasks in ValidatePR += headerCheck in Test
  )
}
object CopyrightHeader extends AutoPlugin with CopyrightHeader