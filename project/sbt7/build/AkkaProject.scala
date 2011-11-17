/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

import com.weiglewilczek.bnd4sbt.BNDPlugin
import java.io.File
import java.util.jar.Attributes
import java.util.jar.Attributes.Name._
import sbt._
import sbt.CompileOrder._
import sbt.ScalaProject._
import spde._

class AkkaParentProject(info: ProjectInfo) extends ParentProject(info) with ExecProject with DocParentProject { akkaParent =>

  // -------------------------------------------------------------------------------------------------------------------
  // Compile settings
  // -------------------------------------------------------------------------------------------------------------------

  val scalaCompileSettings =
    Seq("-deprecation",
        //"-Xmigration",
        "-optimise",
        "-encoding", "utf8")

  val javaCompileSettings = Seq("-Xlint:unchecked")

  system[String]("akka.mode").update("test")

  // -------------------------------------------------------------------------------------------------------------------
  // All repositories *must* go here! See ModuleConigurations below.
  // -------------------------------------------------------------------------------------------------------------------

  object Repositories {
    lazy val AkkaRepo               = MavenRepository("Akka Repository", "http://akka.io/repository")
    lazy val CodehausRepo           = MavenRepository("Codehaus Repo", "http://repository.codehaus.org")
    lazy val GuiceyFruitRepo        = MavenRepository("GuiceyFruit Repo", "http://guiceyfruit.googlecode.com/svn/repo/releases/")
    lazy val JBossRepo              = MavenRepository("JBoss Repo", "http://repository.jboss.org/nexus/content/groups/public/")
    lazy val JavaNetRepo            = MavenRepository("java.net Repo", "http://download.java.net/maven/2")
    lazy val SonatypeSnapshotRepo   = MavenRepository("Sonatype OSS Repo", "http://oss.sonatype.org/content/repositories/releases")
    lazy val GlassfishRepo          = MavenRepository("Glassfish Repo", "http://download.java.net/maven/glassfish")
    lazy val ScalaToolsRelRepo      = MavenRepository("Scala Tools Releases Repo", "http://scala-tools.org/repo-releases")
    lazy val DatabinderRepo         = MavenRepository("Databinder Repo", "http://databinder.net/repo")
    lazy val ScalaToolsSnapshotRepo = MavenRepository("Scala-Tools Snapshot Repo", "http://scala-tools.org/repo-snapshots")
    lazy val SunJDMKRepo            = MavenRepository("WP5 Repository", "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo")
    lazy val TwitterRepo            = MavenRepository("Twitter Public Repo", "http://maven.twttr.com")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // ModuleConfigurations
  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // must be resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.
  // Therefore, if repositories are defined, this must happen as def, not as val.
  // -------------------------------------------------------------------------------------------------------------------

  import Repositories._
  lazy val jettyModuleConfig       = ModuleConfiguration("org.eclipse.jetty", sbt.DefaultMavenRepository)
  lazy val guiceyFruitModuleConfig = ModuleConfiguration("org.guiceyfruit", GuiceyFruitRepo)
  lazy val glassfishModuleConfig   = ModuleConfiguration("org.glassfish", GlassfishRepo)
  lazy val jbossModuleConfig       = ModuleConfiguration("org.jboss", JBossRepo)
  lazy val jerseyModuleConfig      = ModuleConfiguration("com.sun.jersey", JavaNetRepo)
  lazy val jerseyContrModuleConfig = ModuleConfiguration("com.sun.jersey.contribs", JavaNetRepo)
  lazy val multiverseModuleConfig  = ModuleConfiguration("org.multiverse", CodehausRepo)
  lazy val nettyModuleConfig       = ModuleConfiguration("org.jboss.netty", JBossRepo)
  lazy val scalaTestModuleConfig   = ModuleConfiguration("org.scalatest", ScalaToolsRelRepo)
  lazy val spdeModuleConfig        = ModuleConfiguration("us.technically.spde", DatabinderRepo)
  lazy val processingModuleConfig  = ModuleConfiguration("org.processing", DatabinderRepo)
  lazy val sjsonModuleConfig       = ModuleConfiguration("net.debasishg", ScalaToolsRelRepo)
  lazy val redisModuleConfig       = ModuleConfiguration("net.debasishg", ScalaToolsRelRepo)
  lazy val mongoModuleConfig       = ModuleConfiguration("com.mongodb.async", ScalaToolsRelRepo)
  lazy val twitterUtilModuleConfig = ModuleConfiguration("com.twitter", TwitterRepo)
  lazy val beanstalkModuleConfig   = ModuleConfiguration("beanstalk", AkkaRepo)
  lazy val lzfModuleConfig         = ModuleConfiguration("voldemort.store.compress", "h2-lzf", AkkaRepo)
  lazy val vscaladocModuleConfig   = ModuleConfiguration("org.scala-tools", "vscaladoc", "1.1-md-3", AkkaRepo)
  lazy val objenesisModuleConfig   = ModuleConfiguration("org.objenesis", sbt.DefaultMavenRepository)
  lazy val jdmkModuleConfig        = ModuleConfiguration("com.sun.jdmk", SunJDMKRepo)
  lazy val jmxModuleConfig         = ModuleConfiguration("com.sun.jmx", SunJDMKRepo)
  lazy val jmsModuleConfig         = ModuleConfiguration("javax.jms", JBossRepo)
  lazy val jsr311ModuleConfig      = ModuleConfiguration("javax.ws.rs", "jsr311-api", sbt.DefaultMavenRepository)
  lazy val zookeeperModuleConfig   = ModuleConfiguration("org.apache.hadoop.zookeeper", AkkaRepo)
  lazy val protobufModuleConfig    = ModuleConfiguration("com.google.protobuf", AkkaRepo)
  lazy val zkclientModuleConfig    = ModuleConfiguration("zkclient", AkkaRepo)
  lazy val camelCoreModuleConfig   = ModuleConfiguration("org.apache.camel", "camel-core", AkkaRepo)
  lazy val camelJettyModuleConfig  = ModuleConfiguration("org.apache.camel", "camel-jetty", AkkaRepo)

  // -------------------------------------------------------------------------------------------------------------------
  // Versions
  // -------------------------------------------------------------------------------------------------------------------
  lazy val CAMEL_VERSION         = "2.7.1"
  lazy val CAMEL_PATCH_VERSION   = "2.7.1.1"
  lazy val SPRING_VERSION        = "3.0.5.RELEASE"
  lazy val JACKSON_VERSION       = "1.8.0"
  lazy val JERSEY_VERSION        = "1.3"
  lazy val MULTIVERSE_VERSION    = "0.6.2"
  lazy val SCALATEST_VERSION     = "1.6.1"
  lazy val JETTY_VERSION         = "7.4.0.v20110414"
  lazy val JAVAX_SERVLET_VERSION = "3.0"
  lazy val LOGBACK_VERSION       = "0.9.28"
  lazy val SLF4J_VERSION         = "1.6.0"
  lazy val ZOOKEEPER_VERSION     = "3.4.0"

  // -------------------------------------------------------------------------------------------------------------------
  // Dependencies
  // -------------------------------------------------------------------------------------------------------------------

  object Dependencies {

     // Compile
    lazy val activemq         = "org.apache.activemq"         % "activemq-core"           % "5.4.2" % "compile" // ApacheV2
    lazy val beanstalk        = "beanstalk"                   % "beanstalk_client"        % "1.4.5" //New BSD
    lazy val bookkeeper       = "org.apache.hadoop.zookeeper" % "bookkeeper"              % ZOOKEEPER_VERSION //ApacheV2
    lazy val camel_core       = "org.apache.camel"            % "camel-core"              % CAMEL_PATCH_VERSION % "compile" //ApacheV2

    lazy val camel_jetty      = "org.apache.camel"            % "camel-jetty"             % CAMEL_PATCH_VERSION % "compile" // ApacheV2
    lazy val camel_jms        = "org.apache.camel"            % "camel-jms"               % CAMEL_VERSION % "compile" //ApacheV2
    lazy val commons_codec    = "commons-codec"               % "commons-codec"           % "1.4" % "compile" //ApacheV2
    lazy val commons_io       = "commons-io"                  % "commons-io"              % "2.0.1" % "compile" //ApacheV2
    lazy val javax_servlet_30 = "org.glassfish"               % "javax.servlet"           % JAVAX_SERVLET_VERSION % "provided" //CDDL v1
    lazy val jetty            = "org.eclipse.jetty"           % "jetty-server"            % JETTY_VERSION % "provided" //Eclipse license
    lazy val jetty_util       = "org.eclipse.jetty"           % "jetty-util"              % JETTY_VERSION % "compile" //Eclipse license
    lazy val jetty_xml        = "org.eclipse.jetty"           % "jetty-xml"               % JETTY_VERSION % "compile" //Eclipse license
    lazy val jetty_servlet    = "org.eclipse.jetty"           % "jetty-servlet"           % JETTY_VERSION % "compile" //Eclipse license

    lazy val guicey           = "org.guiceyfruit"             % "guice-all"               % "2.0" % "compile" //ApacheV2
    lazy val h2_lzf           = "voldemort.store.compress"    % "h2-lzf"                  % "1.0" % "compile" //ApacheV2
    lazy val jackson          = "org.codehaus.jackson"        % "jackson-mapper-asl"      % JACKSON_VERSION % "compile" //ApacheV2
    lazy val jackson_core     = "org.codehaus.jackson"        % "jackson-core-asl"        % JACKSON_VERSION % "compile" //ApacheV2
    lazy val jersey_server    = "com.sun.jersey"              % "jersey-server"           % JERSEY_VERSION % "provided" //CDDL v1
    lazy val jersey           = "com.sun.jersey"              % "jersey-core"             % JERSEY_VERSION % "compile" //CDDL v1
    lazy val jersey_json      = "com.sun.jersey"              % "jersey-json"             % JERSEY_VERSION % "compile" //CDDL v1
    lazy val jersey_contrib   = "com.sun.jersey.contribs"     % "jersey-scala"            % JERSEY_VERSION % "compile" //CDDL v1

    lazy val jsr250           = "javax.annotation"            % "jsr250-api"              % "1.0" % "compile" //CDDL v1
    lazy val jsr311           = "javax.ws.rs"                 % "jsr311-api"              % "1.1" % "compile" //CDDL v1
    lazy val mongo            = "com.mongodb.async"           % "mongo-driver_2.9.0-1"    % "0.2.6"            //ApacheV2
    lazy val multiverse       = "org.multiverse"              % "multiverse-alpha"        % MULTIVERSE_VERSION % "compile" //ApacheV2
    lazy val netty            = "org.jboss.netty"             % "netty"                   % "3.2.4.Final" % "compile" //ApacheV2
    lazy val osgi_core        = "org.osgi"                    % "org.osgi.core"           % "4.2.0" //ApacheV2
    lazy val protobuf         = "com.google.protobuf"         % "protobuf-java"           % "2.4.1" % "compile" //New BSD
    lazy val redis            = "net.debasishg"               % "redisclient_2.9.0"       % "2.3.1"            //ApacheV2
    lazy val sjson            = "net.debasishg"               %% "sjson"                  % "0.11" % "compile" //ApacheV2
    lazy val sjson_test       = "net.debasishg"               %% "sjson"                  % "0.11" % "test"    //ApacheV2
    lazy val slf4j            = "org.slf4j"                   % "slf4j-api"               % SLF4J_VERSION        // MIT
    lazy val spring_beans     = "org.springframework"         % "spring-beans"            % SPRING_VERSION % "compile" //ApacheV2
    lazy val spring_context   = "org.springframework"         % "spring-context"          % SPRING_VERSION % "compile" //ApacheV2

    lazy val spring_jms       = "org.springframework"         % "spring-jms"              % SPRING_VERSION % "compile" //ApacheV2
    lazy val stax_api         = "javax.xml.stream"            % "stax-api"                % "1.0-2"        % "compile" //ApacheV2
    lazy val twitter_util_core= "com.twitter"                 % "util-core"               % "1.8.1" // ApacheV2
    lazy val logback          = "ch.qos.logback"              % "logback-classic"         % "0.9.28"       % "runtime" //MIT
    lazy val log4j            = "log4j"                       % "log4j"                   % "1.2.15"          //ApacheV2
    lazy val zookeeper        = "org.apache.hadoop.zookeeper" % "zookeeper"               % ZOOKEEPER_VERSION //ApacheV2
    lazy val zookeeper_lock   = "org.apache.hadoop.zookeeper" % "zookeeper-recipes-lock"  % ZOOKEEPER_VERSION //ApacheV2
    lazy val zkClient         = "zkclient"                    % "zkclient"                % "0.3"             //ApacheV2

    // Test
    lazy val multiverse_test  = "org.multiverse"              % "multiverse-alpha"        % MULTIVERSE_VERSION % "test" //ApacheV2
    lazy val commons_coll     = "commons-collections"         % "commons-collections"     % "3.2.1"            % "test" //ApacheV2
    lazy val testJetty        = "org.eclipse.jetty"           % "jetty-server"            % JETTY_VERSION      % "test" //Eclipse license
    lazy val testJettyWebApp  = "org.eclipse.jetty"           % "jetty-webapp"            % JETTY_VERSION      % "test" //Eclipse license
    lazy val junit            = "junit"                       % "junit"                   % "4.5"              % "test" //Common Public License 1.0
    lazy val mockito          = "org.mockito"                 % "mockito-all"             % "1.8.1"            % "test" //MIT
    lazy val scalatest        = "org.scalatest"               %% "scalatest"              % SCALATEST_VERSION  % "test" //ApacheV2
    lazy val scalacheck       = "org.scala-tools.testing"     %% "scalacheck"             % "1.9"              % "test" //New BSD
    lazy val testLogback      = "ch.qos.logback"              % "logback-classic"         % LOGBACK_VERSION    % "test" // EPL 1.0 / LGPL 2.1
    lazy val camel_spring     = "org.apache.camel"            % "camel-spring"            % CAMEL_VERSION      % "test" //ApacheV2
    lazy val commonsMath      = "org.apache.commons"          % "commons-math"            % "2.1"              % "test" //ApacheV2

  }

  // -------------------------------------------------------------------------------------------------------------------
  // Subprojects
  // -------------------------------------------------------------------------------------------------------------------

  lazy val akka_actor             = project("akka-actor",             "akka-actor",             new AkkaActorProject(_))
  lazy val akka_testkit           = project("akka-testkit",           "akka-testkit",           new AkkaTestkitProject(_),                akka_actor)
  lazy val akka_actor_tests       = project("akka-actor-tests",       "akka-actor-tests",       new AkkaActorTestsProject(_),             akka_testkit)
  lazy val akka_stm               = project("akka-stm",               "akka-stm",               new AkkaStmProject(_),                    akka_actor, akka_testkit)
  lazy val akka_http              = project("akka-http",              "akka-http",              new AkkaHttpProject(_),                   akka_actor, akka_testkit)
  lazy val akka_slf4j             = project("akka-slf4j",             "akka-slf4j",             new AkkaSlf4jProject(_),                  akka_actor, akka_testkit)
  lazy val akka_cluster           = project("akka-cluster",           "akka-cluster",           new AkkaClusterProject(_),                akka_stm, akka_actor_tests)
  lazy val akka_durable_mailboxes = project("akka-durable-mailboxes", "akka-durable-mailboxes", new AkkaDurableMailboxesParentProject(_), akka_cluster)

  lazy val akka_camel             = project("akka-camel",             "akka-camel",             new AkkaCamelProject(_),                  akka_actor, akka_slf4j)
  lazy val akka_camel_typed       = project("akka-camel-typed",       "akka-camel-typed",       new AkkaCamelTypedProject(_),             akka_actor, akka_slf4j, akka_camel)
  //lazy val akka_spring            = project("akka-spring",            "akka-spring",            new AkkaSpringProject(_),                 akka_cluster, akka_camel)
  lazy val akka_kernel            = project("akka-kernel",            "akka-kernel",            new AkkaKernelProject(_),                 akka_cluster, akka_http, akka_slf4j, akka_camel_typed)

  lazy val akka_sbt_plugin        = project("akka-sbt-plugin",        "akka-sbt-plugin",        new AkkaSbtPluginProject(_))
  lazy val akka_tutorials         = project("akka-tutorials",         "akka-tutorials",         new AkkaTutorialsParentProject(_),        akka_actor)
  lazy val akka_samples           = project("akka-samples",           "akka-samples",           new AkkaSamplesParentProject(_))

  // -------------------------------------------------------------------------------------------------------------------
  // Miscellaneous
  // -------------------------------------------------------------------------------------------------------------------

  override def disableCrossPaths = true

  // add the sh action since it doesn't exist in ParentProject
  lazy val sh = task { args =>  execOut { Process("sh" :: "-c" :: args.mkString(" ") :: Nil) } }

  // -------------------------------------------------------------------------------------------------------------------
  // Scaladocs
  // -------------------------------------------------------------------------------------------------------------------

  override def apiProjectDependencies = dependencies.toList - akka_samples - akka_sbt_plugin

  // -------------------------------------------------------------------------------------------------------------------
  // Publishing
  // -------------------------------------------------------------------------------------------------------------------

  override def managedStyle = ManagedStyle.Maven

  lazy val akkaPublishRepository  = systemOptional[String]("akka.publish.repository", "default")
  lazy val akkaPublishCredentials = systemOptional[String]("akka.publish.credentials", "none")

  if (akkaPublishCredentials.value != "none") Credentials(akkaPublishCredentials.value, log)

  def publishToRepository = {
    val repoUrl = akkaPublishRepository.value
    if (repoUrl != "default") Resolver.url("Akka Publish Repository", new java.net.URL(repoUrl))
    else Resolver.file("Local Maven Repository", Path.userHome / ".m2" / "repository" asFile)
  }

  val publishTo = publishToRepository

  override def pomExtra = {
    <inceptionYear>2009</inceptionYear>
    <url>http://akka.io</url>
    <organization>
      <name>Typesafe Inc.</name>
      <url>http://www.typesafe.com</url>
    </organization>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
  }

  override def artifacts = Set(Artifact(artifactID, "pom", "pom"))

  override def deliverProjectDependencies = (super.deliverProjectDependencies.toList
                                             - akka_samples.projectID
                                             - akka_tutorials.projectID
                                             - akkaDist.projectID
                                             - akka_sbt_plugin.projectID)

  // -------------------------------------------------------------------------------------------------------------------
  // Build release
  // -------------------------------------------------------------------------------------------------------------------

  val localReleasePath = outputPath / "release" / version.toString
  val localReleaseRepository = Resolver.file("Local Release", localReleasePath / "repository" asFile)

  override def otherRepositories = super.otherRepositories ++ Seq(localReleaseRepository)

  lazy val publishRelease = {
    val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
    publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
  }

  lazy val buildRelease = task {
    log.info("Built release.")
    None
  } dependsOn (publishRelease, releaseApi, releaseDocs, releaseDownloads, releaseDist)

  lazy val releaseApi = task {
    val apiSources = ((apiOutputPath ##) ***)
    val apiPath = localReleasePath / "api" / "akka" / version.toString
    FileUtilities.copy(apiSources.get, apiPath, log).left.toOption
  } dependsOn (api)

  lazy val releaseDocs = task {
    val docsBuildPath = docsPath / "_build"
    val docsHtmlSources = ((docsBuildPath / "html" ##) ***)
    val docsPdfSources = (docsBuildPath / "latex" ##) ** "*.pdf"
    val docsOutputPath = localReleasePath / "docs" / "akka" / version.toString
    FileUtilities.copy(docsHtmlSources.get, docsOutputPath, log).left.toOption orElse
    FileUtilities.copy(docsPdfSources.get, docsOutputPath, log).left.toOption
  } dependsOn (docs)

  lazy val releaseDownloads = task {
    val distArchives = akkaDist.akkaActorsDist.distArchive +++ akkaDist.akkaCoreDist.distArchive
    val downloadsPath = localReleasePath / "downloads"
    FileUtilities.copy(distArchives.get, downloadsPath, log).left.toOption
  } dependsOn (dist)

  lazy val releaseDist = task {
    val distArchives = akkaDist.akkaActorsDist.distExclusiveArchive +++ akkaDist.akkaCoreDist.distExclusiveArchive
    val distPath = localReleasePath / "dist"
    FileUtilities.copy(distArchives.get, distPath, log).left.toOption
  } dependsOn (dist)

  lazy val dist = task { None } // dummy task

  // -------------------------------------------------------------------------------------------------------------------
  // akka-actor sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaActorProject(info: ProjectInfo) extends AkkaDefaultProject(info) with OsgiProject with AutoCompilerPlugins {
    override def bndExportPackage = super.bndExportPackage ++ Seq("com.eaio.*;version=3.2")
    val cont = compilerPlugin("org.scala-lang.plugins" % "continuations" % buildScalaVersion)
    override def compileOptions = super.compileOptions ++ compileOptions("-P:continuations:enable")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-stm sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaStmProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val multiverse = Dependencies.multiverse

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest

    override def deliverProjectDependencies =
      super.deliverProjectDependencies.toList - akka_testkit.projectID
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-cluster sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaClusterProject(info: ProjectInfo) extends AkkaDefaultProject(info) with MultiJvmTests {
    val bookkeeper     = Dependencies.bookkeeper
    val commons_codec  = Dependencies.commons_codec
    val commons_io     = Dependencies.commons_io
    val guicey         = Dependencies.guicey
    val h2_lzf         = Dependencies.h2_lzf
    val jackson        = Dependencies.jackson
    val jackson_core   = Dependencies.jackson_core
    val log4j          = Dependencies.log4j
    val netty          = Dependencies.netty
    val protobuf       = Dependencies.protobuf
    val sjson          = Dependencies.sjson
    val zookeeper      = Dependencies.zookeeper
    val zookeeper_lock = Dependencies.zookeeper_lock
    val zkClient       = Dependencies.zkClient

    // test dependencies
    val scalatest      = Dependencies.scalatest
    val junit          = Dependencies.junit

    // multi jvm tests
    lazy val clusterTest = multiJvmTest
    lazy val clusterRun  = multiJvmRun

    lazy val networkTestsEnabled = systemOptional[Boolean]("akka.test.network", false)

    // test task runs normal tests and then all multi-jvm tests
    lazy val normalTest = super.testAction
    override def multiJvmTestAllAction = super.multiJvmTestAllAction dependsOn (normalTest)
    override def testAction = task { None } dependsOn (normalTest, multiJvmTestAll)

    override def multiJvmOptions = Seq("-Xmx256M")

    override def multiJvmExtraOptions(className: String) = {
      val confFiles = (testSourcePath ** (className + ".conf")).get
      if (!confFiles.isEmpty) {
        val filePath = confFiles.toList.head.absolutePath
        Seq("-Dakka.config=" + filePath)
      } else Seq.empty
    }

    lazy val replicationTestsEnabled = systemOptional[Boolean]("cluster.test.replication", false)

    override def testOptions =
      super.testOptions ++ {
        if (!replicationTestsEnabled.value) Seq(testFilter("Replication"))
        else Seq.empty
      } ++ {
        if (!networkTestsEnabled.value) Seq(TestFilter(test => !test.endsWith("NetworkTest")))
        else Seq.empty
      }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-http sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaHttpProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val jsr250           = Dependencies.jsr250
    val javax_servlet30  = Dependencies.javax_servlet_30
    val jetty            = Dependencies.jetty
    val jersey           = Dependencies.jersey_server
    val jsr311           = Dependencies.jsr311
    val commons_codec    = Dependencies.commons_codec

    // testing
    val junit     = Dependencies.junit
    val mockito   = Dependencies.mockito
    val scalatest = Dependencies.scalatest

    override def deliverProjectDependencies =
      super.deliverProjectDependencies.toList - akka_testkit.projectID
  }

  // -------------------------------------------------------------------------------------------------------------------
  // The akka-durable-mailboxes sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaDurableMailboxesParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_mailboxes_common =
      project("akka-mailboxes-common",  "akka-mailboxes-common",  new AkkaMailboxesCommonProject(_),  akka_cluster)
    lazy val akka_redis_mailbox =
      project("akka-redis-mailbox",     "akka-redis-mailbox",     new AkkaRedisMailboxProject(_),     akka_mailboxes_common)
    lazy val akka_mongo_mailbox =
      project("akka-mongo-mailbox",     "akka-mongo-mailbox",     new AkkaMongoMailboxProject(_),     akka_mailboxes_common)
    lazy val akka_file_mailbox =
      project("akka-file-mailbox",      "akka-file-mailbox",      new AkkaFileMailboxProject(_),      akka_mailboxes_common)
    lazy val akka_beanstalk_mailbox =
      project("akka-beanstalk-mailbox", "akka-beanstalk-mailbox", new AkkaBeanstalkMailboxProject(_), akka_mailboxes_common)
    lazy val akka_zookeeper_mailbox =
      project("akka-zookeeper-mailbox", "akka-zookeeper-mailbox", new AkkaZooKeeperMailboxProject(_), akka_mailboxes_common)
  }

  class AkkaMailboxesCommonProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    // test dependencies
    val scalatest = Dependencies.scalatest
  }

  class AkkaRedisMailboxProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val redis = Dependencies.redis

    lazy val redisTestsEnabled = systemOptional[Boolean]("mailbox.test.redis", false)

    override def testOptions =
      super.testOptions ++ (if (!redisTestsEnabled.value) Seq(testFilter("Redis")) else Seq.empty)
  }

  class AkkaMongoMailboxProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val mongo = Dependencies.mongo
    val twitter = Dependencies.twitter_util_core

    lazy val mongoTestsEnabled = systemOptional[Boolean]("mailbox.test.mongo", true)

    override def testOptions =
      super.testOptions ++ (if (!mongoTestsEnabled.value) Seq(testFilter("Mongo")) else Seq.empty)
  }

  class AkkaFileMailboxProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaBeanstalkMailboxProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val beanstalk = Dependencies.beanstalk

    lazy val beanstalkTestsEnabled = systemOptional[Boolean]("mailbox.test.beanstalk", false)

    override def testOptions =
      super.testOptions ++ (if (!beanstalkTestsEnabled.value) Seq(testFilter("Beanstalk")) else Seq.empty)
  }

  class AkkaZooKeeperMailboxProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  // -------------------------------------------------------------------------------------------------------------------
  // akka-camel sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info) {

    val camel_core = Dependencies.camel_core

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest
    val logback   = Dependencies.testLogback

    override def testOptions = createTestFilter( _.endsWith("Test"))
  }

   // -------------------------------------------------------------------------------------------------------------------
  // akka-camel-typed sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaCamelTypedProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val camel_core       = Dependencies.camel_core

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest
    val logback   = Dependencies.testLogback

    override def testOptions = createTestFilter( _.endsWith("Test"))
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-kernel sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaKernelProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val jetty            = Dependencies.jetty
    val jetty_util       = Dependencies.jetty_util
    val jetty_xml        = Dependencies.jetty_xml
    val jetty_servlet    = Dependencies.jetty_servlet
    val jackson_core     = Dependencies.jackson_core
    val jersey           = Dependencies.jersey
    val jersey_contrib   = Dependencies.jersey_contrib
    val jersey_json      = Dependencies.jersey_json
    val jersey_server    = Dependencies.jersey_server
    val stax_api         = Dependencies.stax_api
  }


  // -------------------------------------------------------------------------------------------------------------------
  // akka-spring sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSpringProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val spring_beans     = Dependencies.spring_beans
    val spring_context   = Dependencies.spring_context

    // testing
    val camel_spring = Dependencies.camel_spring
    val junit        = Dependencies.junit
    val scalatest    = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-sbt-plugin sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSbtPluginProject(info: ProjectInfo) extends PluginProject(info) {
    val srcManagedScala = "src_managed" / "main" / "scala"

    lazy val addSettings = systemOptional[Boolean]("akka.release", false)

    lazy val generateAkkaSbtPlugin = {
      val cleanSrcManaged = cleanTask(srcManagedScala) named ("clean src_managed")
      task {
        info.parent match {
          case Some(project: ParentProject) =>
            xsbt.FileUtilities.write((srcManagedScala / "AkkaProject.scala").asFile,
                                     GenerateAkkaSbtPlugin(project, addSettings.value))
          case _ =>
        }
        None
      } dependsOn cleanSrcManaged
    }

    override def mainSourceRoots = super.mainSourceRoots +++ (srcManagedScala ##)
    override def compileAction = super.compileAction dependsOn(generateAkkaSbtPlugin)

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }

  object GenerateAkkaSbtPlugin {
  def apply(project: ParentProject, addSettings: Boolean): String = {
    val extraConfigs = {
      if (addSettings) Set(ModuleConfiguration("se.scalablesolutions.akka", Repositories.AkkaRepo))
      else Set.empty[ModuleConfiguration]
    }
    val akkaModules = project.subProjects.values.map(_.name).flatMap{
      case "akka-sbt-plugin" => Iterator.empty
      case s if s.startsWith("akka-") => Iterator.single(s.drop(5))
      case _ => Iterator.empty
    }
    val (repos, configs) = (project.moduleConfigurations ++ extraConfigs).foldLeft((Set.empty[String], Set.empty[String])) {
      case ((repos, configs), ModuleConfiguration(org, name, ver, MavenRepository(repoName, repoPath))) =>
        val repoId = repoName.replaceAll("""[^a-zA-Z]""", "_")
        val configId = org.replaceAll("""[^a-zA-Z]""", "_") +
                         (if (name == "*") "" else ("_" + name.replaceAll("""[^a-zA-Z0-9]""", "_") +
                           (if (ver == "*") "" else ("_" + ver.replaceAll("""[^a-zA-Z0-9]""", "_")))))
        (repos + ("  lazy val "+repoId+" = MavenRepository(\""+repoName+"\", \""+repoPath+"\")"),
        configs + ("  lazy val "+configId+" = ModuleConfiguration(\""+org+"\", \""+name+"\", \""+ver+"\", "+repoId+")"))
      case (x, _) => x
    }
    """|import sbt._
       |
       |object AkkaRepositories {
       |%s
       |}
       |
       |trait AkkaBaseProject extends BasicScalaProject {
       |  import AkkaRepositories._
       |
       |%s
       |}
       |
       |trait AkkaProject extends AkkaBaseProject {
       |  val akkaVersion = "%s"
       |
       |
       |  def akkaModule(module: String) = "se.scalablesolutions.akka" %% ("akka-" + module) %% akkaVersion
       |
       |  val akkaActor = akkaModule("actor")
       |}
       |""".stripMargin.format(repos.mkString("\n"),
                               configs.mkString("\n"),
                               project.version.toString,
                               akkaModules.map("\"" + _ + "\"").mkString(", "))
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Samples
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSampleAntsProject(info: ProjectInfo) extends DefaultSpdeProject(info) {
    override def disableCrossPaths = true
    override def spdeSourcePath = mainSourcePath / "spde"

    lazy val sourceArtifact = Artifact(this.artifactID, "src", "jar", Some("sources"), Nil, None)
    lazy val docsArtifact = Artifact(this.artifactID, "doc", "jar", Some("docs"), Nil, None)
    override def packageDocsJar = this.defaultJarPath("-docs.jar")
    override def packageSrcJar  = this.defaultJarPath("-sources.jar")
    override def packageToPublishActions = super.packageToPublishActions ++ Seq(this.packageDocs, this.packageSrc)

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }

  class AkkaSampleChatProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaSampleFSMProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaSampleCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val activemq      = Dependencies.activemq
    val camel_jetty   = Dependencies.camel_jetty
    val camel_jms     = Dependencies.camel_jms
    val spring_jms    = Dependencies.spring_jms
    val commons_codec = Dependencies.commons_codec

    override def ivyXML = {
      <dependencies>
        <exclude module="slf4j-api" />
        <dependency org="org.apache.camel" name="camel-core" rev={CAMEL_PATCH_VERSION} />
      </dependencies>
    }

    override def testOptions = createTestFilter( _.endsWith("Test"))
  }

  class AkkaSampleHelloProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaSampleOsgiProject(info: ProjectInfo) extends AkkaDefaultProject(info) with BNDPlugin {
    val osgiCore = Dependencies.osgi_core
    override protected def bndPrivatePackage = List("sample.osgi.*")
    override protected def bndBundleActivator = Some("sample.osgi.Activator")
  }

  class AkkaSampleRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaSamplesParentProject(info: ProjectInfo) extends ParentProject(info) {
    override def disableCrossPaths = true

    lazy val akka_sample_ants = project("akka-sample-ants", "akka-sample-ants",
      new AkkaSampleAntsProject(_), akka_stm)
   // lazy val akka_sample_chat = project("akka-sample-chat", "akka-sample-chat",
   //   new AkkaSampleChatProject(_), akka_cluster)
    lazy val akka_sample_fsm = project("akka-sample-fsm", "akka-sample-fsm",
      new AkkaSampleFSMProject(_), akka_actor)
    // lazy val akka_sample_hello = project("akka-sample-hello", "akka-sample-hello",
    //   new AkkaSampleHelloProject(_), akka_kernel)
    lazy val akka_sample_osgi = project("akka-sample-osgi", "akka-sample-osgi",
      new AkkaSampleOsgiProject(_), akka_actor)
   // lazy val akka_sample_remote = project("akka-sample-remote", "akka-sample-remote",
   //   new AkkaSampleRemoteProject(_), akka_cluster)
    lazy val akka_sample_camel = project("akka-sample-camel", "akka-sample-camel",
      new AkkaSampleCamelProject(_), akka_actor, akka_kernel)

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Tutorials
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTutorialFirstProject(info: ProjectInfo) extends AkkaTutorialProject(info)

  class AkkaTutorialSecondProject(info: ProjectInfo) extends AkkaTutorialProject(info)

  class AkkaTutorialProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    def doNothing = task { None }
    override def publishLocalAction = doNothing
    override def deliverLocalAction = doNothing
    override def publishAction = doNothing
    override def deliverAction = doNothing
    override lazy val publishRelease = doNothing
  }

  class AkkaTutorialsParentProject(info: ProjectInfo) extends ParentProject(info) {
    override def disableCrossPaths = true

    lazy val akka_tutorial_first = project("akka-tutorial-first", "akka-tutorial-first",
      new AkkaTutorialFirstProject(_), akka_actor)

    lazy val akka_tutorial_second = project("akka-tutorial-second", "akka-tutorial-second",
      new AkkaTutorialSecondProject(_), akka_actor)

    def doNothing = task { None }
    override def publishLocalAction = doNothing
    override def deliverLocalAction = doNothing
    override def publishAction = doNothing
    override def deliverAction = doNothing
    lazy val publishRelease = doNothing
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-testkit sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTestkitProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val scalatest = Dependencies.scalatest
    val scalacheck = Dependencies.scalacheck
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-actor-tests sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaActorTestsProject(info: ProjectInfo) extends AkkaDefaultProject(info) with AutoCompilerPlugins {
    // testing
    val junit           = Dependencies.junit
    val scalatest       = Dependencies.scalatest
    val multiverse_test = Dependencies.multiverse_test // StandardLatch
    val protobuf        = Dependencies.protobuf
    val jackson         = Dependencies.jackson
    val sjson           = Dependencies.sjson
    val commonsMath     = Dependencies.commonsMath
    val mockito         = Dependencies.mockito
    override def compileOptions = super.compileOptions ++ compileOptions("-P:continuations:enable")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-slf4j sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSlf4jProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val slf4j   = Dependencies.slf4j

    override def deliverProjectDependencies =
      super.deliverProjectDependencies.toList - akka_testkit.projectID
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Test options
  // -------------------------------------------------------------------------------------------------------------------

  lazy val integrationTestsEnabled = systemOptional[Boolean]("integration.tests",false)
  lazy val stressTestsEnabled = systemOptional[Boolean]("stress.tests",false)

  // -------------------------------------------------------------------------------------------------------------------
  // Default project
  // -------------------------------------------------------------------------------------------------------------------

  import com.github.olim7t.sbtscalariform._
  class AkkaDefaultProject(info: ProjectInfo) extends DefaultProject(info) with McPom with ScalariformPlugin {

    override def scalariformOptions = Seq(
      //VerboseScalariform,
      AlignParameters(true),
      CompactStringConcatenation(false),
      IndentPackageBlocks(true),
      FormatXml(true),
      PreserveSpaceBeforeArguments(false),
      DoubleIndentClassDeclaration(false),
      RewriteArrowSymbols(true),
      AlignSingleLineCaseStatements(true),
      SpaceBeforeColon(false),
      PreserveDanglingCloseParenthesis(false),
      IndentSpaces(2),
      IndentLocalDefs(false)
//      MaxArrowIndent(40),
//      SpaceInsideBrackets(false),
//      SpaceInsideParentheses(false),
      //SpacesWithinPatternBinders(true)
    )

    override def disableCrossPaths = true

    override def compileOptions = super.compileOptions ++ scalaCompileSettings.map(CompileOption)
    override def javaCompileOptions = super.javaCompileOptions ++ javaCompileSettings.map(JavaCompileOption)

    lazy val sourceArtifact = Artifact(this.artifactID, "src", "jar", Some("sources"), Nil, None)
    lazy val docsArtifact = Artifact(this.artifactID, "doc", "jar", Some("docs"), Nil, None)
    override def runClasspath = super.runClasspath +++ (AkkaParentProject.this.info.projectPath / "config")
    override def testClasspath = super.testClasspath +++ (AkkaParentProject.this.info.projectPath / "config")
    override def packageDocsJar = this.defaultJarPath("-docs.jar")
    override def packageSrcJar  = this.defaultJarPath("-sources.jar")
    override def packageToPublishActions = super.packageToPublishActions ++ Seq(this.packageDocs, this.packageSrc)
    override def pomPostProcess(node: scala.xml.Node): scala.xml.Node = mcPom(AkkaParentProject.this.moduleConfigurations)(super.pomPostProcess(node))

    lazy val excludeTestsProperty = systemOptional[String]("akka.test.exclude", "")

    def excludeTests = {
      val exclude = excludeTestsProperty.value
      if (exclude.isEmpty) Seq.empty else exclude.split(",").toSeq
    }

    def testFilter(containing: String) = TestFilter(test => !test.name.contains(containing))

    override def testOptions = super.testOptions ++ excludeTests.map(exclude => TestFilter(test => !test.contains(exclude)))

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }

    /**
     * Used for testOptions, possibility to enable the running of integration and or stresstests
     *
     * To enable set true and disable set false
     * set integration.tests true
     * set stress.tests true
     */
    def createTestFilter(defaultTests: (String) => Boolean) = { TestFilter({
        case s: String if defaultTests(s) => true
        case s: String if integrationTestsEnabled.value => s.endsWith("TestIntegration")
        case s: String if stressTestsEnabled.value      => s.endsWith("TestStress")
        case _ => false
      }) :: Nil
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Distribution
  // -------------------------------------------------------------------------------------------------------------------

  lazy val akkaDist = project("dist", "akka-dist", new AkkaDistParentProject(_))

  class AkkaDistParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akkaActorsDist = project("actors", "akka-dist-actors", new AkkaActorsDistProject(_), akka_actor)

    lazy val akkaCoreDist = project("core", "akka-dist-core", new AkkaCoreDistProject(_),
                                    akkaActorsDist, akka_cluster, akka_http, akka_slf4j, akka_testkit, akka_actor_tests)

//    lazy val akkaMicrokernelDist = project("microkernel", "akka-dist-microkernel", new AkkaMicrokernelDistProject(_),
//                                           akkaCoreDist, akka_kernel, akka_samples)

    def doNothing = task { None }
    override def publishLocalAction = doNothing
    override def deliverLocalAction = doNothing
    override def publishAction = doNothing
    override def deliverAction = doNothing

    class AkkaActorsDistProject(info: ProjectInfo) extends DefaultProject(info) with DistDocProject {
      def distName = "akka-actors"

      override def distDocName = "akka"

      override def distConfigSources = (akkaParent.info.projectPath / "config" ##) * "*"

      override def distAction = super.distAction dependsOn (distTutorials)

      val distTutorialsPath = distDocPath / "tutorials"

      lazy val distTutorials = task {
        val tutorials = Set(akka_tutorials.akka_tutorial_first,
                            akka_tutorials.akka_tutorial_second)

        tutorials.map { tutorial =>
          val tutorialPath = (tutorial.info.projectPath ##)
          val tutorialFilterOut = ((tutorial.outputPath ##) ***)
          val tutorialSources = (tutorialPath ***) --- tutorialFilterOut
          val tutorialOutputPath = distTutorialsPath / tutorial.name
          copyPaths(tutorialSources, tutorialOutputPath)
        }.foldLeft(None: Option[String])(_ orElse _)
      } dependsOn (distBase)
    }

    class AkkaCoreDistProject(info: ProjectInfo)extends DefaultProject(info) with DistProject {
      def distName = "akka-core"
    }

    class AkkaMicrokernelDistProject(info: ProjectInfo) extends DefaultProject(info) with DistProject {
      def distName = "akka-microkernel"

      override def distScriptSources = akkaParent.info.projectPath / "scripts" / "microkernel" * "*"

//      override def distClasspath = akka_kernel.runClasspath

//      override def projectDependencies = akka_kernel.topologicalSort

//      override def distAction = super.distAction dependsOn (distSamples)

//      val distSamplesPath = distDocPath / "samples"

      // lazy val distSamples = task {
      //   val demo = akka_samples.akka_sample_hello.jarPath
      //   val samples = Set(//akka_samples.akka_sample_camel
      //                     akka_samples.akka_sample_hello)
      //                     //akka_samples.akka_sample_security)

      //   def copySamples[P <: DefaultProject](samples: Set[P]) = {
      //     samples.map { sample =>
      //       val sampleOutputPath = distSamplesPath / sample.name
      //       val binPath = sampleOutputPath / "bin"
      //       val configPath = sampleOutputPath / "config"
      //       val deployPath = sampleOutputPath / "deploy"
      //       val libPath = sampleOutputPath / "lib"
      //       val srcPath = sampleOutputPath / "src"
      //       val confs = sample.info.projectPath / "config" ** "*.*"
      //       val scripts = akkaParent.info.projectPath / "scripts" / "samples" * "*"
      //       val libs = sample.managedClasspath(Configurations.Runtime)
      //       val deployed = sample.jarPath
      //       val sources = sample.packageSourcePaths
      //       copyFiles(confs, configPath) orElse
      //       copyScripts(scripts, binPath) orElse
      //       copyFiles(libs, libPath) orElse
      //       copyFiles(deployed, deployPath) orElse
      //       copyPaths(sources, srcPath)
      //     }.foldLeft(None: Option[String])(_ orElse _)
      //   }

      //   copyFiles(demo, distDeployPath) orElse
      //   copySamples(samples)
      // } dependsOn (distBase)
    }
  }
}

trait OsgiProject extends BNDPlugin { self: DefaultProject =>
  override def bndExportPackage = Seq("akka.*;version=%s".format(projectVersion.value))
}

trait McPom { self: DefaultProject =>
  import scala.xml._

  def mcPom(mcs: Set[ModuleConfiguration])(node: Node): Node = {

    def cleanUrl(url: String) = url match {
      case null                => ""
      case ""                  => ""
      case u if u endsWith "/" => u
      case u                   => u + "/"
    }

    val oldRepos =
      (node \\ "project" \ "repositories" \ "repository").map { n =>
        cleanUrl((n \ "url").text) -> (n \ "name").text
      }.toList

    val newRepos =
      mcs.filter(_.resolver.isInstanceOf[MavenRepository]).map { m =>
        val r = m.resolver.asInstanceOf[MavenRepository]
        cleanUrl(r.root) -> r.name
      }

    val repos = Map((oldRepos ++ newRepos): _*).map { pair =>
      <repository>
        <id>{pair._2.toSeq.filter(_.isLetterOrDigit).mkString}</id>
        <name>{pair._2}</name>
        <url>{pair._1}</url>
      </repository>
    }

    def rewrite(pf: PartialFunction[Node, Node])(ns: Seq[Node]): Seq[Node] = for(subnode <- ns) yield subnode match {
      case e: Elem =>
        if (pf isDefinedAt e) pf(e)
        else Elem(e.prefix, e.label, e.attributes, e.scope, rewrite(pf)(e.child):_*)
      case other => other
    }

    val rule: PartialFunction[Node,Node] = if ((node \\ "project" \ "repositories" ).isEmpty) {
      case Elem(prefix, "project", attribs, scope, children @ _*) =>
           Elem(prefix, "project", attribs, scope, children ++ <repositories>{repos}</repositories>:_*)
    } else {
      case Elem(prefix, "repositories", attribs, scope, children @ _*) =>
           Elem(prefix, "repositories", attribs, scope, repos.toList: _*)
    }

    rewrite(rule)(node.theSeq)(0)
  }
}
