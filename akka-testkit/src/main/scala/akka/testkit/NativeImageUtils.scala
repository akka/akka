/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import akka.actor.DynamicAccess
import akka.actor.ExtendedActorSystem
import akka.actor.ExtensionId
import akka.actor.Scheduler
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.serialization.Serializer
import akka.util.ccompat.JavaConverters._
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.Config
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.ClassInfoList
import io.github.classgraph.MethodInfo

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.stream.Collectors

/**
 * INTERNAL API
 */
@InternalApi
object NativeImageUtils {

  // https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Reflection/#manual-configuration
  val Constructor = "<init>"
  val ModuleField = ReflectField("MODULE$")

  // represents https://www.graalvm.org/docs/reference-manual/native-image/assets/reflect-config-schema-v1.0.0.json
  @JsonInclude(Include.NON_DEFAULT)
  final case class ReflectConfigEntry(
      name: String, // FQCN
      methods: Seq[ReflectMethod] = Seq.empty,
      queriedMethods: Seq[ReflectMethod] = Seq.empty,
      fields: Seq[ReflectField] = Seq.empty,
      allDeclaredClasses: Boolean = false,
      allDeclaredMethods: Boolean = false,
      allDeclaredFields: Boolean = false,
      allDeclaredConstructors: Boolean = false,
      allPublicClasses: Boolean = false,
      allPublicMethods: Boolean = false,
      allPublicFields: Boolean = false,
      allPublicConstructors: Boolean = false,
      allRecordComponents: Boolean = false,
      allNestMembers: Boolean = false,
      allSigners: Boolean = false,
      allPermittedSubclasses: Boolean = false,
      queryAllDeclaredMethods: Boolean = false,
      queryAllDeclaredConstructors: Boolean = false,
      queryAllPublicMethods: Boolean = false,
      queryAllPublicConstructors: Boolean = false,
      unsafeAllocate: Boolean = false,
      condition: Option[Condition] = None)

  @JsonInclude(Include.NON_DEFAULT)
  final case class ReflectMethod(name: String, parameterTypes: Seq[String] = Seq.empty)
  final case class ReflectField(name: String)
  final case class Condition(typeReachable: String)

  def metadataDirFor(akkaModule: String): Path = {
    val repoRoot: Path = {
      if (Files.exists(Paths.get("akka-actor"))) Paths.get("")
      else if (Files.exists(Paths.get("../akka-actor"))) Paths.get("../")
      else throw new RuntimeException("Couldn't figure out akka repo root directory")
    }

    repoRoot.resolve(s"$akkaModule/src/main/resources/META-INF/native-image/com/typesafe/akka/$akkaModule")
  }

  /**
   * Generate GraalVM/NativeImage metadata by scanning the classpath for dynamically loaded extension points of Akka.
   * @param metadataDir The place to write metadata files (usually module resources `/META-INF/native-image/organization/artifactId/`
   * @param additionalEntries Additional, up front known entries for the current module, to add
   * @param packageNames The packages to scan
   */
  def writeMetadata(metadataDir: Path, additionalEntries: Seq[ReflectConfigEntry], packageNames: Seq[String]): Unit = {
    val metadataJson = generateMetadata(packageNames, additionalEntries)
    if (!Files.exists(metadataDir)) Files.createDirectories(metadataDir)
    Files.writeString(
      reflectConfigFile(metadataDir),
      metadataJson,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING)

  }

  /**
   * For use in tests, throws if existing metadata in given dir does exist, else returns the pre-existing metadata and the
   * metadata scanned from current classpath for comparing with test library comparison utils.
   */
  def verifyMetadata(
      metadataDir: Path,
      additionalEntries: Seq[ReflectConfigEntry],
      packageNames: Seq[String]): (String, String) = {
    val configFile = reflectConfigFile(metadataDir)
    if (!Files.exists(configFile))
      throw new IllegalArgumentException(
        s"No previous metadata file [$configFile] exists, generate one using 'writeMetadata' first")
    val currentMetadata = generateMetadata(packageNames, additionalEntries)
    val existingMetadata = Files.readString(configFile, StandardCharsets.UTF_8)
    (existingMetadata, currentMetadata)
  }

  def generateMetadata(packageNames: Seq[String], additionalEntries: Seq[ReflectConfigEntry]): String = {
    val scanResult = new ClassGraph()
    // .verbose() // Log to stderr
      .enableAllInfo() // Scan classes, methods, fields, annotations
      .acceptPackages(packageNames: _*)
      .scan()

    // FIXME dispatcher types?

    // extensions are actually the ExtensionIds accessed reflectively
    val extensions = concreteClassesToJsonAdt(scanResult.getClassesImplementing(classOf[ExtensionId[_]])) {
      extensionClass =>
        // FIXME what about Java, should we look for module field explicitly to decide?
        Some(ReflectConfigEntry(extensionClass.getName, fields = Seq(ModuleField)))
    }

    // serializer loading uses the first constructor found out of these signatures
    val possibleSerializerConstructorParamLists = Seq(
      Seq(classOf[ExtendedActorSystem].getName),
      Seq(classOf[akka.actor.ActorSystem].getName),
      Seq(classOf[akka.actor.ClassicActorSystemProvider].getName),
      Seq(),
      Seq(classOf[akka.actor.ExtendedActorSystem].getName, classOf[java.lang.String].getName),
      Seq(classOf[akka.actor.ActorSystem].getName, classOf[java.lang.String].getName),
      Seq(classOf[akka.actor.ClassicActorSystemProvider].getName, classOf[java.lang.String].getName))

    val serializers = concreteClassesToJsonAdt(scanResult.getClassesImplementing(classOf[Serializer])) {
      serializerClass =>
        // find the first one, which Akka will use, according to Serializer API doc, instead of listing all constructors
        val paramListAkkaWillUse = possibleSerializerConstructorParamLists
          .find(
            paramList =>
              serializerClass
                .getDeclaredMethodInfo(Constructor)
                .asScala
                .toVector
                .map(_.getParameterInfo.toSeq.map(_.getTypeSignatureOrTypeDescriptor.toString))
                .contains(paramList))
          .getOrElse(throw new RuntimeException(
            s"Serializer implementation ${serializerClass.getName} does not have any constructor Akka will recognize"))
        Some(
          ReflectConfigEntry(
            serializerClass.getName,
            methods = Seq(ReflectMethod(Constructor, parameterTypes = paramListAkkaWillUse))))
    }

    val schedulers = concreteClassesToJsonAdt(scanResult.getClassesImplementing(classOf[Scheduler])) { scheduler =>
      // not verifying, expecting that the right constructor will be there
      Some(
        ReflectConfigEntry(
          scheduler.getName,
          methods = Seq(
            ReflectMethod(
              Constructor,
              parameterTypes = Seq(
                classOf[Config].getName,
                classOf[LoggingAdapter].getName,
                classOf[java.util.concurrent.ThreadFactory].getName)))))
    }

    val classicRouterConfigs =
      concreteClassesToJsonAdt(scanResult.getClassesImplementing(classOf[akka.routing.RouterConfig])) { router =>
        val definedConstructors = router.getMethodInfo(Constructor)
        // we support config + dynamic access or just config
        val reflectConstructors =
          definedConstructors.stream().collect(Collectors.toList[MethodInfo]).asScala.toVector.flatMap {
            definedConstructor =>
              val paramTypes =
                definedConstructor.getParameterInfo.toSeq.map(_.getTypeSignatureOrTypeDescriptor.toString)
              if (paramTypes == Seq(classOf[Config].getName) || paramTypes == Seq(
                    classOf[Config].getName,
                    classOf[DynamicAccess].getName)) {
                Some(ReflectMethod(Constructor, parameterTypes = paramTypes))
              } else None
          }
        Some(ReflectConfigEntry(router.getName, methods = reflectConstructors))
      }

    val mailBoxTypes = concreteClassesToJsonAdt(scanResult.getClassesImplementing(classOf[akka.dispatch.MailboxType])) {
      mailboxType =>
        Some(
          ReflectConfigEntry(
            mailboxType.getName,
            methods = Seq(
              ReflectMethod(
                Constructor,
                parameterTypes = Seq(classOf[akka.actor.ActorSystem.Settings].getName, classOf[Config].getName)))))

    }

    val loggingFilters =
      concreteClassesToJsonAdt(scanResult.getClassesImplementing(classOf[akka.event.LoggingFilter])) { filter =>
        Some(
          ReflectConfigEntry(
            filter.getName,
            methods = Seq(
              ReflectMethod(
                Constructor,
                parameterTypes =
                  Seq(classOf[akka.actor.ActorSystem.Settings].getName, classOf[akka.event.EventStream].getName)))))
      }

    // FIXME we could have separate/extensible to define these in the modules they belong, but convenient here for now
    //       without having to list the upstream additions in each module
    val configCheckers =
      concreteClassesToJsonAdt(scanResult.getSubclasses("akka.cluster.JoinConfigCompatChecker")) { checker =>
        Some(ReflectConfigEntry(checker.getName, methods = Seq(ReflectMethod(Constructor))))
      }

    val typedExtensions = concreteClassesToJsonAdt(scanResult.getClassesImplementing("akka.actor.typed.ExtensionId")) {
      extensionClass =>
        Some(ReflectConfigEntry(extensionClass.getName, fields = Seq(ModuleField)))
    }

    val allConfig = additionalEntries ++ mailBoxTypes ++ extensions ++ serializers ++ schedulers ++
      classicRouterConfigs ++ loggingFilters ++ configCheckers ++ typedExtensions

    val mapper =
      JsonMapper.builder().addModule(DefaultScalaModule).configure(SerializationFeature.INDENT_OUTPUT, true).build()
    mapper.writeValueAsString(allConfig)
  }

  private def concreteClassesToJsonAdt(classInfoList: ClassInfoList)(
      f: ClassInfo => Option[ReflectConfigEntry]): Seq[ReflectConfigEntry] =
    classInfoList
      .stream()
      .map[Option[ReflectConfigEntry]](
        classInfo =>
          if (classInfo.isAbstract || classInfo.isInterface) None
          else if (isATestFile(classInfo)) None
          else f(classInfo))
      .collect(Collectors.toList[Option[ReflectConfigEntry]])
      .asScala
      .toVector
      .flatten

  private def reflectConfigFile(parentDir: Path): Path = parentDir.resolve("reflect-config.json")

  private def isATestFile(classInfo: ClassInfo): Boolean = {
    // This probably isn't water tight, and a bit Akka specific, but tricky because sbt actually bundles up classes in some jar file on Test/run but not
    // when test or testOnly
    val sourceFile = classInfo.getSourceFile
    val classPath = classInfo.getClasspathElementFile.toString

    sourceFile.endsWith("Spec.scala") || sourceFile.endsWith("Test.java") || classPath.endsWith("-tests.jar") || classPath
      .endsWith("test-classes")
  }

}
