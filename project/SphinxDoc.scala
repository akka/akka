/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
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
    sphinxInputs in Sphinx <<= sphinxInputs in Sphinx in LocalProject(AkkaBuild.docs.id) map { inputs => inputs.copy(tags = inputs.tags :+ "online") },
    // don't regenerate the pdf, just reuse the akka-docs version
    generatedPdf in Sphinx <<= generatedPdf in Sphinx in LocalProject(AkkaBuild.docs.id) map identity,
    generatedEpub in Sphinx <<= generatedEpub in Sphinx in LocalProject(AkkaBuild.docs.id) map identity
  )

  def docsSettings = Seq(
    sourceDirectory in Sphinx <<= baseDirectory / "rst",
    watchSources <++= (sourceDirectory in Sphinx, excludeFilter in Global) map { (source, excl) =>
      source descendantsExcept ("*.rst", excl) get
    },
    sphinxPackages in Sphinx <+= baseDirectory { _ / "_sphinx" / "pygments" },
    // copy akka-contrib/docs into our rst_preprocess/contrib (and apply substitutions)
    preprocess in Sphinx <<= (preprocess in Sphinx,
      baseDirectory in AkkaBuild.contrib,
      target in preprocess in Sphinx,
      cacheDirectory,
      preprocessExts in Sphinx,
      preprocessVars in Sphinx,
      streams) map { (orig, src, target, cacheDir, exts, vars, s) =>
      val contribSrc = Map("contribSrc" -> "../../../akka-contrib")
      simplePreprocess(src / "docs", target / "contrib", cacheDir / "sphinx" / "preprocessed-contrib", exts, vars ++ contribSrc, s.log)
      orig
    },
    enableOutput in generatePdf in Sphinx := true,
    enableOutput in generateEpub in Sphinx := true,
    unmanagedSourceDirectories in Test <<= sourceDirectory in Sphinx apply { _ ** "code" get }
  )

  // pre-processing settings for sphinx
  lazy val sphinxPreprocessing = inConfig(Sphinx)(Seq(
    target in preprocess <<= baseDirectory / "rst_preprocessed",
    preprocessExts := Set("rst", "py"),
    // customization of sphinx @<key>@ replacements, add to all sphinx-using projects
    // add additional replacements here
    preprocessVars <<= (scalaVersion, version) { (s, v) =>
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
        "github" -> GitHub.url(v)
      )
    },
    preprocess <<= (sourceDirectory, target in preprocess, cacheDirectory, preprocessExts, preprocessVars, streams) map {
      (src, target, cacheDir, exts, vars, s) => simplePreprocess(src, target, cacheDir / "sphinx" / "preprocessed", exts, vars, s.log)
    },
    sphinxInputs <<= (sphinxInputs, preprocess) map { (inputs, preprocessed) => inputs.copy(src = preprocessed) }
  )) ++ Seq(
    cleanFiles <+= target in preprocess in Sphinx
  )
}
