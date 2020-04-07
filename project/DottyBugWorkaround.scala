/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys._
import DottySupportInternal._
import dotty.tools.sbtplugin.DottyPlugin.autoImport.isDotty

object DottyBugWorkaround {
  def apply(projectId: String): Seq[Setting[_]] = Hack(projectId) match {
    case Nil => Nil
    case settings => Seq(Compile, Test).flatMap(SourceReplacer.settings) ++ settings
  }

  /** Type from `projectId -> Seq[Setting[_]]` for that project
   * a `Hack` named `h1234` is used to workaround the dotty bug lampepfl/dotty#1234,
   * and (TODO) should be removed when the corresponding dotty bug is fixed */
  private type Hack = PartialFunction[String, Seq[Setting[_]]]

  private object Hack {
    private lazy val all = Seq(h8554, h8599, h8588, h7212, h8593, h8631, h8652)
    def apply(projectId: String): Seq[Setting[_]] = all.flatMap(_.lift(projectId).toSeq.flatten)

    // lampepfl/dotty#8554 - Need this to fix akka-actor-tests/test:compile errors in dotty:
    // Bad symbolic reference. A signature refers to Nullable/T in package javax.annotation which is not available.
    // javax.annotation.Nullable is used in jimfs, docker-client, guava,..
    val h8554: Hack = {
      case "akka-actor-tests" => Seq(
        libraryDependencies ifDotty T.add(
          "com.google.code.findbugs" % "jsr305" % "3.0.2" % Test
        )
      )
    }

    val h8599: Hack = {
      case "akka-actor-tests" => Seq(
        Test / sources ifDotty (Test / javaSource) { base =>
          val files = Seq(
            "akka/actor/StashJavaAPITestActors.java",
            "akka/actor/StashJavaAPI.java"
          ).map(base / _)
          T.remove(files: _*)
        },
        Test / sourceReplacers ++= SourceReplacer(
          "scala/akka/actor/ActorWithStashSpec.scala"
          )(_.replace("import org.scalatestplus.junit.JUnitSuiteLike\n", "")
            .replace(
              """
                |@silent
                |class JavaActorWithStashSpec extends StashJavaAPI with JUnitSuiteLike
                |""".stripMargin, "")),
      )
    }

    val h8588: Hack = {
      case "akka-testkit" => Seq(
        Test / sourceReplacers ++=
          SourceReplacer("scala/akka/testkit/AkkaSpec.scala")(_.replace(
          "ConfigFactory.parseMap(map.asJava)",
          "ConfigFactory.parseMap(map.asInstanceOf[Map[String, AnyRef]].asJava)")) ++
          SourceReplacer("scala/akka/testkit/AkkaSpecSpec.scala")(_.replaceAll(
          """("akka\.actor\.debug\..*" ->\s*)true""",
          "$1(true: java.lang.Boolean)"))
      )
      case "akka-actor-tests" =>  Seq(
        Test / sourceReplacers ++= SourceReplacer(
            "scala/akka/actor/FSMActorSpec.scala",
            "scala/akka/event/LoggingReceiveSpec.scala"
          )(_.replaceAll(
          """(ConfigFactory\s*\.parseMap\(Map\(.*->\s*)true""",
          "$1(true: java.lang.Boolean)"))
        )
    }

    val h8593: Hack = {
      case "akka-actor-tests" => Seq(
        Test / sourceReplacers ++= SourceReplacer(
          "java/akka/actor/ActorCreationTest.java"
        )(_.replaceAll(
          """(@Override\s+public void onReceive\(Object message\)) throws Exception""", "$1"))
      )
    }

    val h8631: Hack = {
      case "akka-actor-testkit-typed" => Seq(
        Test / sourceReplacers ++= SourceReplacer(
          "java/akka/actor/testkit/typed/javadsl/BehaviorTestKitTest.java"
        )(_.replace(
          "\n                      return Behaviors.same();",
          "\n                      return (Behavior<Command>) (Object) Behaviors.same();"))
      )
      case "akka-actor-typed-tests" => Seq(
        Test / sourceReplacers ++=
          SourceReplacer(
            "java/akka/actor/typed/javadsl/ActorContextPipeToSelfTest.java"
          )(_.replace(
            "return Behaviors.same();",
            "return (Behavior<String>) (Object) Behaviors.same();")) ++
          SourceReplacer(
            "java/akka/actor/typed/javadsl/BehaviorBuilderTest.java"
          )(_
            .replace("o.foo()", "((One) o).foo()")
            .replaceAll(
          """\(MyList<String> l\) -> \{(\s+)String first = l""",
            "l -> {$1String first = ((MyList<String>) l)")
            .replace("t.getRef()", "((Terminated) t).getRef()")) ++
          SourceReplacer(
            "java/akka/actor/typed/receptionist/ReceptionistApiTest.java"
          )(_.replace("registered.", "((Receptionist.Registered) registered)."))
      )
    }

    val h7212: Hack = {
      case "akka-actor" => Seq(
        Compile / sourceReplacers ++= SourceReplacer.fix7212(
          "scala/akka/actor/setup/ActorSystemSetup.scala",
          "scala/akka/actor/AbstractProps.scala",
          "scala-2.13/akka/util/ByteString.scala")
      )
      case "akka-actor-tests" => Seq(
        Test / sourceReplacers ++= SourceReplacer(
          "java/akka/actor/JavaAPI.java"
        )(_.replaceFirst(
          """@Test\s+public void mustBeAbleToCreateActorWIthNullConstructorParams2\(\)""",
          """@org.junit.Ignore("lampepfl/dotty/7212") $0"""
        ))
      )
      case "akka-actor-testkit-typed" => Seq(
        Compile / sourceReplacers ++= SourceReplacer.fix7212(
          "scala/akka/actor/testkit/typed/javadsl/ManualTime.scala")
      )
      case "akka-actor-typed" => Seq(
        Compile / sourceReplacers ++= SourceReplacer.fix7212(
          "scala/akka/actor/typed/Props.scala")
      )
      case "akka-testkit" => Seq(
        Compile / sourceReplacers ++= SourceReplacer.fix7212(
          "scala/akka/testkit/javadsl/TestKit.scala")
      )
    }
  }

  val h8652: Hack = {
    case "akka-actor-tests" => Seq(
      Test / sourceReplacers ++= SourceReplacer(
        "java/akka/pattern/StatusReplyTest.java"
      )(_.replace(
        "import akka.actor.Actor;", "import akka.actor.Actor$;"
      ).replace("Actor.noSender()", "Actor$.MODULE$.noSender()"))
    )
  }

  /** Given a pair (sourceFile, newFile),
   * `SourceReplacer` is a function used to replace content of `sourceFile` and write the result to `newFile` */
  private type SourceReplacer = (File, File) => Unit
  
  /** A pair: source path => SourceReplacer where path is subPath of `sourceDirectory` */
  private type Path2Replacer = (String, SourceReplacer)
  
  lazy val sourceReplacers = settingKey[Seq[Path2Replacer]]("Source replacers")
  lazy val replaceSources = taskKey[Unit]("Replace sources")

  private object SourceReplacer {
    def settings(c: Configuration) = Seq(
      c / sourceReplacers := Nil,
      c / replaceSources := {
        val src = (c / sourceDirectory).value.toPath
        val srcManaged = (c / sourceManaged).value.toPath
        val s = (c / streams).value
        val replacers = (c / sourceReplacers).value

        if (isDotty.value) {
          val replacerMap = replacers.map {
            case (rel, r) => src.resolve(rel).toFile -> (srcManaged.resolve(rel).toFile, r)
          }.toMap
          val filesToReplace = replacerMap.keySet
          def replace(f: File): Unit = {
            val (newFile, r) = replacerMap(f)
            r(f, newFile)
          }

          // https://www.scala-sbt.org/1.x/docs/Caching.html#Tracked.diffInputs
          Tracked.diffInputs(s.cacheStoreFactory.make("input_diff"), FileInfo.lastModified)(filesToReplace) {
            (inDiff: ChangeReport[File]) =>
              inDiff.modified.foreach(replace)
              inDiff.unmodified.filterNot(replacerMap(_)._1.exists()).foreach(replace)
          }
        }
      },
      c / sources := {
        val old = (c / sources).value
        (c / replaceSources).value
        val src = (c / sourceDirectory).value.toPath
        val srcManaged = (c / sourceManaged).value.toPath

        if (!isDotty.value) old
        else {
          val relocationMap = (c / sourceReplacers).value.map { case (rel, _) =>
            src.resolve(rel).toFile -> srcManaged.resolve(rel).toFile
          }.toMap
          old.map(f => relocationMap.getOrElse(f, f))
        }
      }
    )

    private val r7212 = ScalafixSourceReplacer("github:ohze/scalafix-rules/VarargsHack")
    def fix7212(subPaths: String*): Seq[Path2Replacer] = subPaths.map(_ -> r7212)
    
    def apply(subPaths: String*)(replacer: String => String): Seq[Path2Replacer] =
      subPaths.map {_ -> ((f: File, newFile: File) => IO.write(newFile, replacer(IO.read(f))))}
  }

  private object ScalafixSourceReplacer {
    import scalafix.internal.sbt.ScalafixInterface
    import coursierapi.Repository.{ivy2Local, central}
    import coursierapi.MavenRepository.{of => maven}

    private def sonatype(tpe: String) = maven(s"https://oss.sonatype.org/content/repositories/$tpe")
    private def resolvers = Seq(ivy2Local(), central(), sonatype("public"), sonatype("snapshots"))
    private lazy val scalafixInterface = ScalafixInterface.fromToolClasspath("2.13", Nil, resolvers)()

    def apply(rule: String): SourceReplacer = (f, newFile) => {
      import scalafix.internal.sbt.Arg._

      IO.copyFile(f, newFile)

      val errors = scalafixInterface.withArgs(
        Paths(Seq(newFile.toPath)),
        Rules(Seq(rule))
      ).run()

      errors.foreach(println)
    }
  }
}
