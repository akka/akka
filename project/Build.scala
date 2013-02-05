/*
Copyright 2013 Crossing-Tech

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
   limitations under the License.
 */
import sbt._
import Keys._
import com.typesafe.sbtosgi.OsgiPlugin.{OsgiKeys, osgiSettings}


object OsgiSampleBuild extends Build {

  override lazy val settings =
    super.settings ++
      buildSettings ++
      Seq(
        shellPrompt := {
          s => Project.extract(s).currentProject.id + " > "
        }
      )

  lazy val buildSettings = Seq(
    scalaVersion := "2.10.0",
    resolvers ++= Seq("oss-sonatype-releases" at "https://oss.sonatype.org/content/repositories/releases",
      "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"),
    version := "2.1.0-SNAPSHOT"
  )


  val ActorReferenceCopyTask = TaskKey[Int]("osgi-prepare", "Prepare an Osgi framework and required bundles")

  val ActorReferenceCopyAction = ActorReferenceCopyTask:={
    new File("bundles").mkdir()
    projects.map(_.base).filter(p => (new File(p+"/target/scala-2.10")).exists).foreach(p =>  {
      List("sh", "-c", "cp "+p+"/target/scala-2.10/*.jar bundles") !;
    })
    List("sh", "-c", "cd core; bash ../karaf.sh") !;
  }

  lazy val root = Project(id = "osgi-sample",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
     ActorReferenceCopyAction,
     cleanFiles <+= baseDirectory { base => base / "bundles" },
     cleanFiles <+= baseDirectory { base => base / "apache-karaf-2.3.0" },
      libraryDependencies ++= Seq()
    )
  ) aggregate(api, command, core, uncommons, protobuf)

  lazy val api = Project(id = "api",
    base = file("./api"),
    settings = Project.defaultSettings ++ exports(Seq("akka.osgi.sample.api")) ++ Seq(libraryDependencies ++= Seq(Dependencies.akka_actor))
  )

  lazy val command = Project(id = "command",
    base = file("./command"),
    settings = Project.defaultSettings ++ exports(Seq("akka.osgi.sample.command"), Seq("akka.osgi.sample.api", "org.osgi.framework")) ++ Seq(
      libraryDependencies ++= Dependencies.command,
      OsgiKeys.bundleActivator := Option("akka.osgi.sample.command.Activator")
    )
  ) dependsOn (api)


  lazy val core = Project(id = "core",
    base = file("./core"),
    settings = Project.defaultSettings ++ exports(Seq("akka.osgi.sample.service", "akka.osgi.sample.activation"), defaultImports, Seq("akka.osgi.sample.internal")) ++ Seq(
      libraryDependencies ++= Dependencies.core,
      OsgiKeys.bundleActivator := Option("akka.osgi.sample.activation.Activator")
    )
  ) dependsOn (api)

  lazy val uncommons = Project(id = "uncommons",
    base = file("./uncommons"),
    settings = Project.defaultSettings ++ exports(Seq("org.uncommons.maths.random"), privates = Seq("org.uncommons.maths.binary", "org.uncommons.maths", "org.uncommons.maths.number")) ++ Seq(
      libraryDependencies ++= Dependencies.uncommons,
      version := "1.2.2"
    )
  )

  lazy val protobuf = Project(id = "protobuf",
    base = file("./protobuf"),
    settings = Project.defaultSettings ++ exports(Seq("com.google.protobuf")) ++ Seq(
      libraryDependencies ++= Seq(Dependencies.protobuf),
      version := "2.4.1"
    )
  )

  def exports(packages: Seq[String] = Seq(), imports: Seq[String] = Nil, privates: Seq[String] = Nil) = osgiSettings ++ Seq(
    OsgiKeys.importPackage := imports ++ Seq("*"),
    OsgiKeys.privatePackage := privates,
    OsgiKeys.exportPackage := packages
  )

   def copyFile(source: String, sink: String){
    val src = new java.io.File(source)
    val dest = new java.io.File(sink)
    new java.io.FileOutputStream(dest) getChannel() transferFrom(
      new java.io.FileInputStream(src) getChannel, 0, Long.MaxValue )
  }


  def defaultImports = Seq("!sun.misc", akkaImport(), configImport(), scalaImport())

  def akkaImport(packageName: String = "akka.*") = "%s;version=\"[2.1,2.3)\"".format(packageName)

  def configImport(packageName: String = "com.typesafe.config.*") = "%s;version=\"[0.4.1,1.1.0)\"".format(packageName)

  def protobufImport(packageName: String = "com.google.protobuf.*") = "%s;version=\"[2.4.0,2.5.0)\"".format(packageName)

  def scalaImport(packageName: String = "scala.*") = "%s;version=\"[2.10,2.11)\"".format(packageName)

}

object Dependencies {
  val akka_actor = "com.typesafe.akka" % "akka-actor_2.10" % "2.2-SNAPSHOT"              changing()
  val akka_osgi = "com.typesafe.akka" % "akka-osgi_2.10" % "2.2-SNAPSHOT"  exclude("org.osgi.core", "org.osgi.compendium") changing()
  val akka_remote = "com.typesafe.akka" % "akka-remote_2.10" % "2.2-SNAPSHOT"              changing()
  val akka_cluster = "com.typesafe.akka" % "akka-cluster-experimental_2.10" % "2.2-SNAPSHOT" changing()
  val config = "com.typesafe" % "config" % "1.0.0"

  val osgiCore = "org.osgi" % "org.osgi.core" % "4.3.0"
  val osgiCompendium = "org.osgi" % "org.osgi.compendium" % "4.3.0"
  val core = Seq(akka_actor, akka_osgi, akka_remote, akka_cluster, config, osgiCore, osgiCompendium)
  val command = Seq(akka_actor, osgiCore, osgiCompendium)

  val uncommons_math = "org.uncommons.maths" % "uncommons-maths" % "1.2.2"
  val jcommon = "jfree" % "jcommon" % "1.0.16"
  val jfreechart = "jfree" % "jfreechart" % "1.0.13"
  val uncommons = Seq(uncommons_math, jcommon, jfreechart)

  val protobuf = "com.google.protobuf" % "protobuf-java" % "2.4.1"

}

