/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import akka.ValidatePullRequest.{ValidatePR, additionalTasks}
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._
import de.heikoseeberger.sbtheader.{CommentCreator, HeaderPlugin}
import sbt.Keys._
import sbt._

trait CopyrightHeader extends AutoPlugin {

  override def requires = HeaderPlugin

  override def trigger = allRequirements

  override def projectSettings = Def.settings(
    Seq(Compile, Test).flatMap { config =>
      inConfig(config)(
        Seq(
          headerLicense := Some(HeaderLicense.Custom(headerFor(CurrentYear))),
          headerMappings := headerMappings.value ++ Map(
            HeaderFileType.scala -> cStyleComment,
            HeaderFileType.java -> cStyleComment,
            HeaderFileType("template") -> cStyleComment
          )
        )
      )
    },
    additional
  )

  def additional = Def.settings(
    (compile in Compile) := {
      (headerCreate in Compile).value
      (compile in Compile).value
    },
    (compile in Test) := {
      (headerCreate in Test).value
      (compile in Test).value
    }
  )

  val CurrentYear = java.time.Year.now.getValue.toString
  val CopyrightPattern = "Copyright \\([Cc]\\) (\\d{4}(-\\d{4})?) (Lightbend|Typesafe) Inc. <.*>".r
  val CopyrightHeaderPattern = s"(?s).*${CopyrightPattern}.*".r

  def headerFor(year: String): String =
    s"Copyright (C) $year Lightbend Inc. <https://www.lightbend.com>"

  val cStyleComment = HeaderCommentStyle.cStyleBlockComment.copy(commentCreator = new CommentCreator() {

    import HeaderCommentStyle.cStyleBlockComment.commentCreator

    def updateLightbendHeader(header: String): String = header match {
      case CopyrightHeaderPattern(years, null, _)     =>
        if (years != CurrentYear)
          CopyrightPattern.replaceFirstIn(header, headerFor(years + "-" + CurrentYear))
        else
          CopyrightPattern.replaceFirstIn(header, headerFor(years))
      case CopyrightHeaderPattern(years, endYears, _) =>
        CopyrightPattern.replaceFirstIn(header, headerFor(years.replace(endYears, "-" + CurrentYear)))
      case _                                          =>
        header
    }

    override def apply(text: String, existingText: Option[String]): String = {
      existingText
        .map(updateLightbendHeader)
        .getOrElse(commentCreator(text, existingText))
        .trim
    }
  })
}

object CopyrightHeader extends CopyrightHeader

object CopyrightHeaderInPr extends CopyrightHeader {

  override val additional = Def.settings(
      additionalTasks in ValidatePR += headerCheck in Compile,
      additionalTasks in ValidatePR += headerCheck in Test
    )
}
