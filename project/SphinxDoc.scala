/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import com.typesafe.sbt.site.SphinxSupport
import com.typesafe.sbt.site.SphinxSupport.{ enableOutput, generatePdf, generatedPdf, generateEpub, generatedEpub, sphinxInputs, sphinxPackages, Sphinx }
import sbt.Keys._
import com.typesafe.sbt.preprocess.Preprocess._
import sbt.LocalProject

object SphinxDoc {

  def akkaSettings = SphinxSupport.settings ++ Seq(
    // generate online version of docs
    sphinxInputs in Sphinx := {
      val inputs = (sphinxInputs in Sphinx in LocalProject(AkkaBuild.docs.id)).value
      inputs.copy(tags = inputs.tags :+ "online")
    },
    // don't regenerate the pdf, just reuse the akka-docs version
    generatedPdf in Sphinx := (generatedPdf in Sphinx in LocalProject(AkkaBuild.docs.id)).value,
    generatedEpub in Sphinx := (generatedEpub in Sphinx in LocalProject(AkkaBuild.docs.id)).value
  )

  def docsSettings = Seq(
    sourceDirectory in Sphinx := baseDirectory.value / "rst",
    watchSources ++= {
      val source = (sourceDirectory in Sphinx).value
      val excl = (excludeFilter in Global).value
      source.descendantsExcept("*.rst", excl).get
    },
    sphinxPackages in Sphinx += baseDirectory.value / "_sphinx" / "pygments",
    // copy akka-contrib/docs into our rst_preprocess/contrib (and apply substitutions)
    preprocess in Sphinx := {
      val s = streams.value

      val contribSrc = Map("contribSrc" -> "../../../akka-contrib")
      simplePreprocess(
        (baseDirectory in AkkaBuild.contrib).value / "docs",
        (target in preprocess in Sphinx).value / "contrib",
        s.cacheDirectory / "sphinx" / "preprocessed-contrib",
        (preprocessExts in Sphinx).value,
        (preprocessVars in Sphinx).value ++ contribSrc,
        s.log)

      (preprocess in Sphinx).value
    },
    enableOutput in generatePdf in Sphinx := true,
    enableOutput in generateEpub in Sphinx := true,
    unmanagedSourceDirectories in Test := ((sourceDirectory in Sphinx).value ** "code").get
  )

  // pre-processing settings for sphinx
  lazy val sphinxPreprocessing = inConfig(Sphinx)(Seq(
    target in preprocess := baseDirectory.value / "rst_preprocessed",
    preprocessExts := Set("rst", "py"),
    // customization of sphinx @<key>@ replacements, add to all sphinx-using projects
    // add additional replacements here
    preprocessVars := {
      val s = scalaVersion.value
      val v = version.value
      val BinVer = """(\d+\.\d+)\.\d+""".r
      Map(
        "version" -> v,
        "scalaVersion" -> s,
        "crossString" -> (s match {
          case BinVer(_) => ""
          case _         => "cross CrossVersion.full"
        }),
        "jarName" -> (s match {
          case BinVer(bv) => "akka-actor_" + bv + "-" + v + ".jar"
          case _          => "akka-actor_" + s + "-" + v + ".jar"
        }),
        "binVersion" -> (s match {
          case BinVer(bv) => bv
          case _          => s
        }),
        "sigarVersion" -> Dependencies.Compile.sigar.revision,
        "sigarLoaderVersion" -> Dependencies.Compile.Provided.sigarLoader.revision,
        "github" -> GitHub.url(v),
        "samples" -> "http://github.com/akka/akka-samples/tree/master",
        "exampleCodeService" -> "https://example.lightbend.com/v1/download"
      )
    },
    preprocess := {
      val s = streams.value
      simplePreprocess(
        sourceDirectory.value,
        (target in preprocess).value,
        s.cacheDirectory / "sphinx" / "preprocessed",
        preprocessExts.value,
        preprocessVars.value,
        s.log)
    },
    sphinxInputs := sphinxInputs.value.copy(src = preprocess.value)
  )) ++ Seq(
    cleanFiles += (target in preprocess in Sphinx).value
  )
}
