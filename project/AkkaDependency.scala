/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._

object AkkaDependency {
  // Property semantics:
  // If akka.sources is set, then the given URI will override everything else
  // else if akka version is "master", then a source dependency to git://github.com/akka/akka.git#master will be used
  // else if akka version is "default", then the hard coded default will be used (jenkins doesn't allow empty values for config axis)
  // else if akka.version is anything else, then the given version will be used

  val defaultAkkaVersion = "2.5.9"
  val akkaVersion = {
    val res = System.getProperty("akka.build.version", defaultAkkaVersion)
    if (res == "default") defaultAkkaVersion
    else res
  }

  // Needs to be a URI like git://github.com/akka/akka.git#master or file:///xyz/akka
  val akkaSourceDependencyUri = {
    val fallback =
      if (akkaVersion == "master") "git://github.com/akka/akka.git#master"
      else ""

    System.getProperty("akka.sources",fallback)
  }
  val shouldUseSourceDependency = akkaSourceDependencyUri != ""

  val akkaRepository = {
    // as a little hacky side effect also disable aggregation of samples
    System.setProperty("akka.build.aggregateSamples", "false")

    uri(akkaSourceDependencyUri)
  }

  implicit class RichProject(project: Project) {
    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addAkkaModuleDependency(module: String, config: String = ""): Project =
      if (shouldUseSourceDependency) {
        val moduleRef = ProjectRef(akkaRepository, module)
        val withConfig: ClasspathDependency =
          if (config == "") moduleRef
          else moduleRef % config

        project.dependsOn(withConfig)
      } else {
        project.settings(libraryDependencies += {
          val dep = "com.typesafe.akka" %% module % akkaVersion
          val withConfig =
            if (config == "") dep
            else dep % config
          withConfig
        })
      }
  }
}
