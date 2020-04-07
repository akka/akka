/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._
import sbt.librarymanagement.VersionNumber
import Def.Initialize
import scala.language.implicitConversions
import dotty.tools.sbtplugin.DottyPlugin.autoImport._
import DottySupport._
import DottySupportInternal._

object DottySupport {
  implicit class ProjectOps(val p: Project) extends AnyVal {
    /**
     * This extension method should be called last when defining project in build.sbt
     *
     * @note We can NOT just use an AutoPlugin with `override def projectSettings` = the following settings
     *       because the execution order of those settings and the settings defined in other places
     *       are out of our control! (limit of sbt)
     *       Ex: `crossScalaVersions += scala3Version` defined in `projectSettings` take no effect!
     *       because after `crossScalaVersions += scala3Version` is executed,
     *       `crossScalaVersions := ...` (defined in akka.Dependencies.Versions) will overwrite the value.
     *       Similar to `libraryDependencies` and `scalacOptions`:
     *       We need access old value of those settings and then transform to a dotty compat new value.
     *       After this transformation, the settings should not be modified */
    def dottySupport(extraConfigs: Configuration*): Project = p
      .settings(
        addSourceDirScalaMajor(Compile),
        addSourceDirScalaMajor(Test),
        crossScalaVersions += scala3Version,
        libraryDependencies ifDotty scalaVersion(LibDepT.apply)
      )
      .settings(Test / scalacOptions ifDotty T.add("-language:postfixOps"))
      .settings((Compile +: Test +: extraConfigs).map(_ / scalacOptions ifDotty scalacOptionsT))
      .settings(DottyBugWorkaround(p.id))
  }

  object ModuleIDOps {
    import Ordering.Implicits._
    import java.lang.String.CASE_INSENSITIVE_ORDER

    /** Need this so that "bin" < "RC1" */
    private implicit val stringOrdering: Ordering[String] = Ordering.comparatorToOrdering(CASE_INSENSITIVE_ORDER)

    private implicit val dottyVersionOrdering: Ordering[VersionNumber] = Ordering.fromLessThan { (x, y) =>
      x.numbers < y.numbers ||
        x.numbers == y.numbers && (
          if (y.tags.isEmpty) x.tags.nonEmpty // 0.24.0-RC1 < 0.24.0
          else x.tags.nonEmpty && x.tags < y.tags // 0.24.0-bin-xx < 0.24.0-RC1
        )
    }

    private val dotty2scalaMap = Seq(
      "0.19.0-RC1" -> "0.24.0-bin-20200422-0db5976-NIGHTLY" -> "2.13.1",
      "0.24.0-bin-20200423-38cb5e3-NIGHTLY" -> "0.26.0" -> "2.13.2"
    ).map { case ((x, y), v) => VersionNumber(x) -> VersionNumber(y) -> CrossVersion.constant(v) }

    private def dotty2scala(scalaVersion: VersionNumber) = dotty2scalaMap.collectFirst {
      case ((x, y), v) if x <= scalaVersion && scalaVersion <= y => v
    }.getOrElse(sys.error(s"Pls update withDottyFullCompat for $scalaVersion in project/DottySupport.scala"))
  }

  implicit class ModuleIDOps(val moduleID: ModuleID) extends AnyVal {
    def withDottyFullCompat(scalaVersion: String): ModuleID =
      if (scalaVersion.startsWith("2.")) moduleID.cross(CrossVersion.patch)
      else moduleID.cross(ModuleIDOps.dotty2scala(VersionNumber(scalaVersion)))
  }

  val scala3Version = "0.24.0"

  /** Add sourceDir to c / unmanagedSourceDirectories
   * where sourceDir is calculated from scala partialVersion by function f.
   * @param f: function from (scalaMajorVersion, scalaMinorVersion) => sourceDir */
  def addScalaSourceDir(c: Configuration)(f: PartialFunction[(Long, Long), String]): Setting[Seq[File]] =
    c / unmanagedSourceDirectories +=
      (c / sourceDirectory).value / f(CrossVersion.partialVersion(scalaVersion.value).get)

  /** Adds `src/{main|test}/scala-{scalaMajorVersion}` source directory.
   * @note add scala-3 for both scala 0.x and 3.x */
  def addSourceDirScalaMajor(c: Configuration = Compile): Setting[Seq[File]] =
    addScalaSourceDir(c) {
      case (2, _) => "scala-2"
      case _      => "scala-3"
    }

  /** Adds `src/{main|test}/scala-{scalaMajorVersion}.{scalaMinorVersion}` source directory
   * @note add scala-2.13 for both scala 2.13 and and dotty (scala 0.x | 3.x) */
  def addSourceDirScalaBin(c: Configuration = Compile): Setting[Seq[File]] =
    addScalaSourceDir(c) {
      // hardcode 2.13 for dotty is safe here because we don't support dotty < 0.18.1
      case (0 | 3, _) => "scala-2.13"
      case (m, n)     => s"scala-$m.$n"
    }
}

private object DottySupportInternal {
  /** a T[A] = A => A is used to transform a `SettingKey[A]` or a `TaskKey[A]` for dotty compat
   * Usage pattern:
   * given a `key: Key[A]` such as
   * + `scalacOptions: TaskKey[Seq[String]]`
   * + `libraryDependencies: SettingKey[Seq[ModuleID]]`
   * and a `t: A => A`,
   * we patch settings of a Project `p` by: {{{
   *   p.settings(key ifDotty t)
   * }}}
   * @see [[SettingKeyOps.ifDotty]], [[TaskKeyOps.ifDotty]] */
  type T[A] = A => A
  object T {
    /** Add `items` into the old Seq[A] */
    def add[A](items: A*): T[Seq[A]] = _ ++ items

    /** Remove `items` from the old Seq[A] */
    def remove[A](items: A*): T[Seq[A]] = _.filterNot(items.contains)

    /** Remove `item` and its next item from the old Seq[A]
      * ex, use remove2("-release") to remove ["-release", "11"] */
    def remove2[A](item: A): T[Seq[A]] = seq => seq.indexOf(item) match {
      case -1 => seq
      case i => seq.patch(i, Nil, 2)
    }
    
    def apply[A](ts: T[A]*): T[A] = ts.reduce(_ andThen _)

    def onlyIf[A](b: Boolean)(t: => T[A]): T[A] = if (b) t else identity[A]
  }
  
  implicit def liftToInitialize[A](v: A): Initialize[T[A]] = Def.valueStrict(Function.const(v))
  implicit def liftToInitialize[A](t: T[A]): Initialize[T[A]] = Def.valueStrict(t)

  implicit class SettingKeyOps[A](val key: SettingKey[A]) extends AnyVal {
    def ifDotty(trans: Initialize[T[A]]): Setting[A] =
      key := T.onlyIf(isDotty.value)(trans.value)(key.value)
  }
  implicit class TaskKeyOps[A](val key: TaskKey[A]) extends AnyVal {
    def ifDotty(trans: Initialize[T[A]]): Setting[Task[A]] =
      key := T.onlyIf(isDotty.value)(trans.value)(key.value)
  }

  val scalacOptionsT: T[Seq[String]] = T(
    T.add("-language:implicitConversions"),
    T.remove("-Xfatal-warnings", "-Xlog-reflective-calls"),
    // dotc has not supported `-release` option yet. lampepfl/dotty#8634
    T.remove2("-release"),
    // dotc don't support multiple `-language:` options.
    // we need convert `-language:o1 -language:o2` to `-language:o1,o2` */
    (opts: Seq[String]) => {
      val prefix = "-language:"
      val (l, r) = opts.partition(_.startsWith(prefix))
      val languageOpt = prefix + l.map(_.stripPrefix(prefix)).mkString(",")
      r :+ languageOpt
    }
  ).andThen(_.distinct)
  
  object LibDepT {
    private val eql: (ModuleID, ModuleID) => Boolean =
      (a, b) => a.organization == b.organization && a.name == b.name

    /** Similar to [[T.remove]] but use a customized equals checking function */
    private def remove(items: ModuleID*): T[Seq[ModuleID]] = _.filterNot(a => items.exists(eql(a, _)))
    
    /** convenient method to create a T that
     *  convert `items` - using function `f` - in the old Seq[A] to construct the new Seq[A] */
    private def trans(items: ModuleID*)(f: ModuleID => ModuleID): T[Seq[ModuleID]] = _.map {
      case a if items.exists(eql(a, _)) => f(a)
      case a => a
    }

    import Dependencies.Compile._

    def apply(scalaVersion: String): T[Seq[ModuleID]] = T(
      trans(
        sslConfigCore,
        "org.scala-lang.modules" %% "scala-java8-compat" % "*",
        jacksonScala,
        Test.scalacheck,
        Docs.sprayJson
      )(_ withDottyCompat scalaVersion),
      trans(
        "com.github.ghik" %% "silencer-lib" % "*"
      )(_ withDottyFullCompat scalaVersion),
      remove("com.github.ghik" %% "silencer-plugin" % "*"),
    )
  }
}
