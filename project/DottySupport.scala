/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._
import Def.Initialize
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import scala.language.implicitConversions
import dotty.tools.sbtplugin.DottyPlugin.autoImport._
import DottySupport._
import DottySupportInternal._

// NOTE: To import sbt projects into IntelliJ with dotty libs:
// + Open sbt Preference window. Add VM params: -Dakka.build.scalaVersion=<scala3Version's value>
// + Regex replace all in build.sbt:
//   Search:     `((akkaModule\("|val root = )(.*\n)(..(?!\.dottySupport\().*\n)*)\n`
//   Replace by: `$1  .dottySupport() // todo remove\n\n`
//   _don't include the ` char_
// + Then reimport sbt projects
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
     *       We need access old value of those settings and transform to a dotty compat new value.
     *       So the after transformation, the settings should not be modified */
    def dottySupport(extraConfigs: Configuration*): Project = p
      .settings(
        addSourceDirScalaMajor(Compile),
        addSourceDirScalaMajor(Test),
        crossScalaVersions += scala3Version,
        libraryDependencies trans scalaVersion(LibTransform(_, useCustomScalatest = true))
      )
      .settings(Test / scalacOptions trans Transform.add("-language:postfixOps"))
      .settings((extraConfigs :+ Compile :+ Test).map(_ / scalacOptions trans ScalacOptionsTransform))
  }

  implicit class ModuleIDOps(val moduleID: ModuleID) extends AnyVal {
    def withDottyFullCompat(scalaVersion: String): ModuleID = {
      CrossVersion.partialVersion(scalaVersion) match {
        case Some((0, n)) if n > 18 && n <= 24 => moduleID.cross(CrossVersion.constant("2.13.1"))
        case Some((0 | 3, _)) =>
          sys.error(s"Pls update DottyFullCompat for $scalaVersion in project/DottySupport.scala")
        case _ => moduleID.cross(CrossVersion.patch)
      }
    }
  }

  // need 0.24 because of https://github.com/lampepfl/dotty/issues/8582
  val scala3Version = "0.24.0-bin-20200408-4cc224b-NIGHTLY"

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
  import Transform._

  /** a Transform[A] is used to transform a `SettingKey[Seq[A]]` for dotty compat
   * Usage pattern:
   * given a `key: Key[Seq[A]]` such as
   * + `scalacOptions: TaskKey[Seq[String]]`
   * + `libraryDependencies: SettingKey[Seq[ModuleID]]`
   * and a `t: Transform[A]`,
   * we patch settings of a Project `p` by: {{{
   *   p.settings(key trans t)
   * }}}
   * @see [[SettingKeyOps.trans]]
   * @see [[TaskKeyOps.trans]] */
  trait Transform[A] extends (Seq[A] => Seq[A]) {
    type T = Transform[A] // convenient type alias
    def onlyWhen(b: Boolean): T = if (b) this else zero
    def run(transformers: T*): T = transformers.fold(zero)(_.andThen(_))
  }
  object Transform {
    private val _zero: Transform[Nothing] = (x: Seq[Nothing]) => x
    def zero[A]: Transform[A] = _zero.asInstanceOf[Transform[A]]

    implicit def pureInit[A](t: Transform[A]): Initialize[Transform[A]] = Def.valueStrict(t)

    implicit def fn2transformer[A](f: Seq[A] => Seq[A]): Transform[A] = new Transform[A] {
      def apply(x: Seq[A]): Seq[A] = f(x)
    }

    /** @return a Transform that remove `items` from the old Seq[A] */
    def remove[A](items: A*): Transform[A] = (_: Seq[A]).filterNot(items.contains)

    /** @return a Transform that remove `items` from the old Seq[A]
     *          using a customized equals checking function */
    def remove[A, B](eql: (A, B) => Boolean, items: B*): Transform[A] =
      (_: Seq[A]).filterNot(a => items.exists(eql(a, _)))

    /** @return a Transform that add `items` into the old Seq[A] */
    def add[A](items: A*): Transform[A] = (_: Seq[A]) ++ items
  }

  /** convenient transformation extension methods
   * instead of {{{
   *   key := {
   *      val old = key.value;
   *      if (!isDotty.value) old
   *      else transformer(old)
   *   }
   * }}} we can write {{{
   *   key trans transformer
   * }}} */
  implicit class SettingKeyOps[A](val key: SettingKey[Seq[A]]) extends AnyVal {
    def trans[T <: Transform[A]](trans: Initialize[T]): Setting[Seq[A]] =
      key := trans.value.onlyWhen(isDotty.value)(key.value)
  }
  implicit class TaskKeyOps[A](val key: TaskKey[Seq[A]]) extends AnyVal {
    def trans[T <: Transform[A]](trans: Initialize[T]): Setting[Task[Seq[A]]] =
      key := trans.value.onlyWhen(isDotty.value)(key.value)
  }

  object ScalacOptionsTransform extends Transform[String] {
    /** remove all option o in `opts` and its next item in scalacOptions
     * ex, use remove2("-release") to remove ["-release", "11"] */
    private def remove2(opts: String*): T =
      (_: Seq[String]).foldLeft(Seq.empty[String] -> false) {
        case ((acc, skip), o) =>
          if (skip) acc -> false
          else if (opts.contains(o)) acc -> true
          else (acc :+ o) -> false
      }._1

    /** dotc don't support multiple `-language:` options
     * we need convert `-language:o1 -language:o2` to `-language:o1,o2` */
    private val language: T = (opts: Seq[String]) => {
      val prefix = "-language:"
      val (l, r) = opts.partition(_.startsWith(prefix))
      val languageOpt = prefix + l.map(_.stripPrefix(prefix)).mkString(",")
      r :+ languageOpt
    }

    /** transform a scalac 2 options to dotc corresponding options
     * @note this function don't attempt to transform all scalac 2 options
     *       but only ones that need for akka dotty support */
    def apply(opts: Seq[String]): Seq[String] =
      run(
        add("-language:implicitConversions"),
        // add("-language:implicitConversions", "-language:Scala2Compat", "-rewrite"),
        remove("-Xfatal-warnings", "-Xlog-reflective-calls"),
        // dotc don't support `-release` option
        // TODO remove when this issue is fixed: https://github.com/lampepfl/dotty/issues/8634
        remove2("-release"),
        language
      )(opts).distinct
  }

  object LibTransform {
    private case class OrgAndName(org: String, name: String)
    private object OrgAndName {
      implicit def from(m: ModuleID): OrgAndName = OrgAndName(m.organization, m.name)
      implicit def from(m: OrganizationArtifactName): OrgAndName = from(m % "_")
    }
  }

  case class LibTransform(
    scalaVersion: String,
    useCustomScalatest: Boolean
  ) extends Transform[ModuleID] {
    import LibTransform._

    /** version suffix for custom build libs.
     *  Need for using custom build version of libs depend on dotty nightly */
    private val versionSuffix: String = {
      val isDottyNightly = !scalaVersion.startsWith("2.") && scalaVersion.length > 10 // 10 == "0.xx.0-bin".length
      if (isDottyNightly) "-dotty" + scalaVersion.substring(10)
      else ""
    }

    /** excludeAll "org.scalatest" if need */
    private def scalatestIntransitive(m: ModuleID): ModuleID =
      if (useCustomScalatest && m.organization == "org.scalatestplus") {
        m.excludeAll("org.scalatest")
      } else m

    private val eql: (ModuleID, OrgAndName) => Boolean =
      (a, b) => a.organization == b.org && a.name == b.name

    /** convenient method to create a Transform that
     *  convert `items` - using function `f` - in the old Seq[A] to the new Seq[A] */
    private def trans(items: Seq[OrgAndName])(f: ModuleID => ModuleID): T = (_: Seq[ModuleID]).map {
      case a if items.exists(eql(a, _)) => f(a)
      case a => a
    }

    /** transform libs that have not published for dotty yet */
    private def needDottyCompat(libs: OrgAndName*): T = trans(libs) { m =>
      scalatestIntransitive(m withDottyCompat scalaVersion)
    }

    private def needDottyFullCompat(libs: OrgAndName*): T = trans(libs) {
      _.withDottyFullCompat(scalaVersion)
    }

    /** transform libs that need a native build for dotty (can't use withDottyCompat)
     * but have not been built for our dotty version so we need a custom build.
     * @see [[useCustomScalatest]] */
    private def needCustomBuild(libs: OrgAndName*): T = trans(libs) { m =>
      scalatestIntransitive(m
        .withOrganization("com.sandinh")
        .withRevision(m.revision + versionSuffix))
    }

    import Dependencies.Compile._
    def apply(old: Seq[ModuleID]): Seq[ModuleID] =
      run(
        needDottyCompat(
          sslConfigCore,
          // java8Compat,
          "org.scala-lang.modules" %% "scala-java8-compat",
          jacksonScala,
          Test.scalacheck,
          Test.scalatestTestNG,
          Test.scalatestMockito,
          Docs.sprayJson
        ),
        needDottyFullCompat(
          "com.github.ghik" %% "silencer-lib"
        ),
        remove(eql,
          "com.github.ghik" %% "silencer-plugin": OrgAndName
        ),
        needCustomBuild(
          Test.scalatest,
          Test.scalatestJUnit,
          Test.scalatestScalaCheck
        )
      )(old)
  }
}
