/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import com.weiglewilczek.bnd4sbt.BNDPlugin
import java.io.File
import java.util.jar.Attributes
import java.util.jar.Attributes.Name._
import sbt._
import sbt.CompileOrder._
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

  // -------------------------------------------------------------------------------------------------------------------
  // All repositories *must* go here! See ModuleConigurations below.
  // -------------------------------------------------------------------------------------------------------------------

  object Repositories {
    lazy val LocalMavenRepo         = MavenRepository("Local Maven Repo", (Path.userHome / ".m2" / "repository").asURL.toString)
    lazy val AkkaRepo               = MavenRepository("Akka Repository", "http://akka.io/repository")
    lazy val CodehausRepo           = MavenRepository("Codehaus Repo", "http://repository.codehaus.org")
    lazy val GuiceyFruitRepo        = MavenRepository("GuiceyFruit Repo", "http://guiceyfruit.googlecode.com/svn/repo/releases/")
    lazy val JBossRepo              = MavenRepository("JBoss Repo", "http://repository.jboss.org/nexus/content/groups/public/")
    lazy val JavaNetRepo            = MavenRepository("java.net Repo", "http://download.java.net/maven/2")
    lazy val SonatypeSnapshotRepo   = MavenRepository("Sonatype OSS Repo", "http://oss.sonatype.org/content/repositories/releases")
    lazy val ScalaToolsRelRepo      = MavenRepository("Scala Tools Releases Repo", "http://scala-tools.org/repo-releases")
    lazy val DatabinderRepo         = MavenRepository("Databinder Repo", "http://databinder.net/repo")
    lazy val ScalaToolsSnapshotRepo = MavenRepository("Scala-Tools Snapshot Repo", "http://scala-tools.org/repo-snapshots")
    lazy val TypesafeRepo           = MavenRepository("Typesafe Repo", "http://repo.typesafe.com/typesafe/releases/")
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
  lazy val servletModuleConfig     = ModuleConfiguration("org.apache.geronimo.specs", sbt.DefaultMavenRepository)
  lazy val jbossModuleConfig       = ModuleConfiguration("org.jboss", JBossRepo)
  lazy val jerseyModuleConfig      = ModuleConfiguration("com.sun.jersey", sbt.DefaultMavenRepository)
  lazy val multiverseModuleConfig  = ModuleConfiguration("org.multiverse", CodehausRepo)
  lazy val nettyModuleConfig       = ModuleConfiguration("org.jboss.netty", JBossRepo)
  lazy val scalaTestModuleConfig   = ModuleConfiguration("org.scalatest", ScalaToolsRelRepo)
  lazy val spdeModuleConfig        = ModuleConfiguration("us.technically.spde", DatabinderRepo)
  lazy val processingModuleConfig  = ModuleConfiguration("org.processing", DatabinderRepo)
  lazy val sjsonModuleConfig       = ModuleConfiguration("net.debasishg", ScalaToolsRelRepo)
  lazy val lzfModuleConfig         = ModuleConfiguration("voldemort.store.compress", "h2-lzf", AkkaRepo)
  lazy val vscaladocModuleConfig   = ModuleConfiguration("org.scala-tools", "vscaladoc", "1.1-md-3", AkkaRepo)
  lazy val aspectWerkzModuleConfig = ModuleConfiguration("org.codehaus.aspectwerkz", "aspectwerkz", "2.2.3", AkkaRepo)
  lazy val objenesisModuleConfig   = ModuleConfiguration("org.objenesis", sbt.DefaultMavenRepository)
  lazy val jsr311ModuleConfig      = ModuleConfiguration("javax.ws.rs", "jsr311-api", sbt.DefaultMavenRepository)

  lazy val redisModuleConfig       = ModuleConfiguration("net.debasishg", ScalaToolsRelRepo)
  lazy val beanstalkModuleConfig   = ModuleConfiguration("beanstalk", AkkaRepo)
  lazy val zkclientModuleConfig    = ModuleConfiguration("zkclient", AkkaRepo)
  lazy val zookeeperModuleConfig   = ModuleConfiguration("org.apache.hadoop.zookeeper", AkkaRepo)
  lazy val zeromqModuleConfig      = ModuleConfiguration("org.zeromq", TypesafeRepo)

  lazy val localMavenRepo          = LocalMavenRepo // Second exception, also fast! ;-)

  // -------------------------------------------------------------------------------------------------------------------
  // Versions
  // -------------------------------------------------------------------------------------------------------------------

  lazy val JACKSON_VERSION       = "1.8.0"
  lazy val JERSEY_VERSION        = "1.3"
  lazy val MULTIVERSE_VERSION    = "0.6.2"
  lazy val SCALATEST_VERSION     = "1.6.1"
  lazy val JETTY_VERSION         = "7.4.0.v20110414"
  lazy val SLF4J_VERSION         = "1.6.0"

  // -------------------------------------------------------------------------------------------------------------------
  // Dependencies
  // -------------------------------------------------------------------------------------------------------------------

  object Dependencies {

    // Compile
    lazy val aopalliance = "aopalliance" % "aopalliance" % "1.0" % "compile" //Public domain

    lazy val aspectwerkz = "org.codehaus.aspectwerkz" % "aspectwerkz" % "2.2.3" % "compile" //ApacheV2

    lazy val commons_codec = "commons-codec" % "commons-codec" % "1.4" % "compile" //ApacheV2

    lazy val commons_io = "commons-io" % "commons-io" % "2.0.1" % "compile" //ApacheV2

    lazy val javax_servlet_30 = "org.apache.geronimo.specs" % "geronimo-servlet_3.0_spec" % "1.0" % "provided" //CDDL v1

    lazy val jetty         = "org.eclipse.jetty" % "jetty-server"  % JETTY_VERSION % "provided" //Eclipse license
    lazy val guicey = "org.guiceyfruit" % "guice-all" % "2.0" % "compile" //ApacheV2

    lazy val h2_lzf = "voldemort.store.compress" % "h2-lzf" % "1.0" % "compile" //ApacheV2

    lazy val jackson          = "org.codehaus.jackson" % "jackson-mapper-asl" % JACKSON_VERSION % "compile" //ApacheV2
    lazy val jackson_core     = "org.codehaus.jackson" % "jackson-core-asl"   % JACKSON_VERSION % "compile" //ApacheV2

    lazy val jersey_server  = "com.sun.jersey"          % "jersey-server" % JERSEY_VERSION % "provided" //CDDL v1

    lazy val jsr250 = "javax.annotation" % "jsr250-api" % "1.0" % "compile" //CDDL v1

    lazy val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile" //CDDL v1

    lazy val multiverse      = "org.multiverse" % "multiverse-alpha" % MULTIVERSE_VERSION % "compile" //ApacheV2
    lazy val multiverse_test = "org.multiverse" % "multiverse-alpha" % MULTIVERSE_VERSION % "test" //ApacheV2

    lazy val netty = "org.jboss.netty" % "netty" % "3.2.5.Final" % "compile" //ApacheV2

    lazy val osgi_core = "org.osgi" % "org.osgi.core" % "4.2.0" //ApacheV2

    lazy val protobuf = "com.google.protobuf" % "protobuf-java" % "2.4.1" % "compile" //New BSD

    lazy val zeromq = "org.zeromq" %% "zeromq-scala-binding" % "0.0.2" // ApacheV2

    lazy val sjson      = "net.debasishg" % "sjson_2.9.0" % "0.11" % "compile" //ApacheV2
    lazy val sjson_test = "net.debasishg" % "sjson_2.9.0" % "0.11" % "test" //ApacheV2

    lazy val slf4j   = "org.slf4j"      % "slf4j-api"       % SLF4J_VERSION
    lazy val logback = "ch.qos.logback" % "logback-classic" % "0.9.28" % "runtime"

    lazy val beanstalk        = "beanstalk"                   % "beanstalk_client"        % "1.4.5" //New BSD
    lazy val redis            = "net.debasishg"               % "redisclient_2.9.0"       % "2.3.1"            //ApacheV2
    lazy val mongoAsync       = "com.mongodb.async"           % "mongo-driver_2.9.0-1"    % "0.2.7"      //ApacheV2

    // Test

    lazy val commons_coll   = "commons-collections"    % "commons-collections" % "3.2.1"           % "test" //ApacheV2
    lazy val commons_math   = "org.apache.commons"     % "commons-math"        % "2.1"             % "test" //ApacheV2

    lazy val testJetty      = "org.eclipse.jetty"      % "jetty-server"        % JETTY_VERSION     % "test" //Eclipse license
    lazy val testJettyWebApp= "org.eclipse.jetty"      % "jetty-webapp"        % JETTY_VERSION     % "test" //Eclipse license

    lazy val junit          = "junit"                  % "junit"               % "4.5"             % "test" //Common Public License 1.0
    lazy val mockito        = "org.mockito"            % "mockito-all"         % "1.8.1"           % "test" //MIT
    lazy val scalatest      = "org.scalatest"          % "scalatest_2.9.0-1"   % SCALATEST_VERSION % "test" //ApacheV2
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Subprojects
  // -------------------------------------------------------------------------------------------------------------------

  lazy val akka_actor       = project("akka-actor",       "akka-actor",       new AkkaActorProject(_))
  lazy val akka_testkit     = project("akka-testkit",     "akka-testkit",     new AkkaTestkitProject(_),              akka_actor)
  lazy val akka_actor_tests = project("akka-actor-tests", "akka-actor-tests", new AkkaActorTestsProject(_),           akka_testkit)
  lazy val akka_stm         = project("akka-stm",         "akka-stm",         new AkkaStmProject(_),                  akka_actor)
  lazy val akka_typed_actor = project("akka-typed-actor", "akka-typed-actor", new AkkaTypedActorProject(_),           akka_stm, akka_actor_tests)
  lazy val akka_remote      = project("akka-remote",      "akka-remote",      new AkkaRemoteProject(_),               akka_typed_actor)
  lazy val akka_durable_mailboxes = project("akka-durable-mailboxes", "akka-durable-mailboxes", new AkkaDurableMailboxesParentProject(_), akka_remote)
  lazy val akka_http        = project("akka-http",        "akka-http",        new AkkaHttpProject(_),                 akka_actor)
  lazy val akka_zeromq      = project("akka-zeromq",      "akka-zeromq",      new AkkaZeroMQProject(_),               akka_actor, akka_testkit)
  lazy val akka_samples     = project("akka-samples",     "akka-samples",     new AkkaSamplesParentProject(_))
  lazy val akka_slf4j       = project("akka-slf4j",       "akka-slf4j",       new AkkaSlf4jProject(_),                akka_actor)
  lazy val akka_tutorials   = project("akka-tutorials",   "akka-tutorials",   new AkkaTutorialsParentProject(_),      akka_actor)

  // -------------------------------------------------------------------------------------------------------------------
  // Miscellaneous
  // -------------------------------------------------------------------------------------------------------------------

  override def disableCrossPaths = true

  // add the sh action since it doesn't exist in ParentProject
  lazy val sh = task { args =>  execOut { Process("sh" :: "-c" :: args.mkString(" ") :: Nil) } }

  // -------------------------------------------------------------------------------------------------------------------
  // Scaladocs
  // -------------------------------------------------------------------------------------------------------------------

  override def apiProjectDependencies = dependencies.toList - akka_samples

  // -------------------------------------------------------------------------------------------------------------------
  // Publishing
  // -------------------------------------------------------------------------------------------------------------------

  override def managedStyle = ManagedStyle.Maven

  lazy val akkaPublishRepository = systemOptional[String]("akka.publish.repository", "default")
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
      <name>Scalable Solutions AB</name>
      <url>http://scalablesolutions.se</url>
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
                                             - akkaDist.projectID)

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
  // akka-actor subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaActorProject(info: ProjectInfo) extends AkkaDefaultProject(info) with OsgiProject with AutoCompilerPlugins {
    override def bndExportPackage = super.bndExportPackage ++ Seq("com.eaio.*;version=3.2")
    override def bndImportPackage = "*" :: Nil
    val cont = compilerPlugin("org.scala-lang.plugins" % "continuations" % buildScalaVersion)
    override def compileOptions = super.compileOptions ++ compileOptions("-P:continuations:enable")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-stm subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaStmProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val multiverse = Dependencies.multiverse

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-typed-actor subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTypedActorProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val aopalliance  = Dependencies.aopalliance
    val aspectwerkz  = Dependencies.aspectwerkz
    val guicey       = Dependencies.guicey

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest

    override def deliverProjectDependencies =
      super.deliverProjectDependencies.toList - akka_actor_tests.projectID ++ Seq(akka_actor_tests.projectID % "test")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-remote subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val commons_codec = Dependencies.commons_codec
    val commons_io    = Dependencies.commons_io
    val guicey        = Dependencies.guicey
    val h2_lzf        = Dependencies.h2_lzf
    val jackson       = Dependencies.jackson
    val jackson_core  = Dependencies.jackson_core
    val netty         = Dependencies.netty
    val protobuf      = Dependencies.protobuf
    val sjson         = Dependencies.sjson

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest

    lazy val networkTestsEnabled = systemOptional[Boolean]("akka.test.network", false)

    override def testOptions = super.testOptions ++ {
      if (!networkTestsEnabled.value) Seq(TestFilter(test => !test.endsWith("NetworkTest")))
      else Seq.empty
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-http subproject
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
  }

  // -------------------------------------------------------------------------------------------------------------------                                       
  // akka-zeromq subproject                                                                                                                                    
  // -------------------------------------------------------------------------------------------------------------------                                       
                                                                                                                                                               
  class AkkaZeroMQProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val protobuf   = Dependencies.protobuf
    val zeromq     = Dependencies.zeromq
    val scalatest  = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // The akka-durable-mailboxes sub-project
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaDurableMailboxesParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_mailboxes_common =
      project("akka-mailboxes-common", "akka-mailboxes-common", new AkkaMailboxesCommonProject(_), akka_remote)
    lazy val akka_redis_mailbox =
      project("akka-redis-mailbox", "akka-redis-mailbox", new AkkaRedisMailboxProject(_), akka_mailboxes_common)
    lazy val akka_file_mailbox =
      project("akka-file-mailbox", "akka-file-mailbox", new AkkaFileMailboxProject(_), akka_mailboxes_common)
    lazy val akka_beanstalk_mailbox =
      project("akka-beanstalk-mailbox", "akka-beanstalk-mailbox", new AkkaBeanstalkMailboxProject(_), akka_mailboxes_common)

    override def disableCrossPaths = true

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }

  class AkkaMailboxesCommonProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    // test dependencies
    val scalatest = Dependencies.scalatest
  }

  class AkkaRedisMailboxProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val redis = Dependencies.redis

    lazy val redisTestsEnabled = systemOptional[Boolean]("mailbox.test.redis", false)

    override def testOptions =
      super.testOptions ++ (if (!redisTestsEnabled.value) Seq(TestFilter(test => !test.name.contains("Redis"))) else Seq.empty)
  }

  class AkkaFileMailboxProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaBeanstalkMailboxProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val beanstalk = Dependencies.beanstalk

    lazy val beanstalkTestsEnabled = systemOptional[Boolean]("mailbox.test.beanstalk", false)

    override def testOptions =
      super.testOptions ++ (if (!beanstalkTestsEnabled.value) Seq(TestFilter(test => !test.name.contains("Beanstalk"))) else Seq.empty)
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Samples
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSampleAntsProject(info: ProjectInfo) extends DefaultSpdeProject(info) {
    override def disableCrossPaths = true
    override def spdeSourcePath = mainSourcePath / "spde"
    override def spde_artifact = "us.technically.spde" % "spde-core_2.9.0-1" % spdeVersion.value

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

  class AkkaSampleRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaSampleChatProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaSampleFSMProject(info: ProjectInfo) extends AkkaDefaultProject(info)

  class AkkaSampleOsgiProject(info: ProjectInfo) extends AkkaDefaultProject(info) with BNDPlugin {
    val osgiCore = Dependencies.osgi_core
    override protected def bndPrivatePackage = List("sample.osgi.*")
    override protected def bndBundleActivator = Some("sample.osgi.Activator")
  }

  class AkkaSamplesParentProject(info: ProjectInfo) extends ParentProject(info) {
    override def disableCrossPaths = true

    lazy val akka_sample_ants = project("akka-sample-ants", "akka-sample-ants",
      new AkkaSampleAntsProject(_), akka_stm)
    lazy val akka_sample_fsm = project("akka-sample-fsm", "akka-sample-fsm",
      new AkkaSampleFSMProject(_), akka_actor)
    lazy val akka_sample_remote = project("akka-sample-remote", "akka-sample-remote",
      new AkkaSampleRemoteProject(_), akka_remote)
    lazy val akka_sample_chat = project("akka-sample-chat", "akka-sample-chat",
      new AkkaSampleChatProject(_), akka_remote)
    lazy val akka_sample_osgi = project("akka-sample-osgi", "akka-sample-osgi",
      new AkkaSampleOsgiProject(_), akka_actor)

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
  // akka-testkit subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTestkitProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val scalatest = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-actor-tests subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaActorTestsProject(info: ProjectInfo) extends AkkaDefaultProject(info) with AutoCompilerPlugins {
    // testing
    val junit           = Dependencies.junit
    val scalatest       = Dependencies.scalatest
    val multiverse_test = Dependencies.multiverse_test // StandardLatch
    val commons_math    = Dependencies.commons_math
    val mockito         = Dependencies.mockito
    override def compileOptions = super.compileOptions ++ compileOptions("-P:continuations:enable")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-slf4j subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSlf4jProject(info: ProjectInfo) extends AkkaDefaultProject(info) {
    val slf4j   = Dependencies.slf4j
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Default project
  // -------------------------------------------------------------------------------------------------------------------

  //import com.github.olim7t.sbtscalariform._
  class AkkaDefaultProject(info: ProjectInfo) extends DefaultProject(info) with McPom /*with ScalariformPlugin*/ {

    /*override def scalariformOptions = Seq(
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
    )*/

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

    override def testOptions = super.testOptions ++ excludeTests.map(exclude => TestFilter(test => !test.contains(exclude)))

    lazy val publishRelease = {
      val releaseConfiguration = new DefaultPublishConfiguration(localReleaseRepository, "release", false)
      publishTask(publishIvyModule, releaseConfiguration) dependsOn (deliver, publishLocal, makePom)
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Distribution
  // -------------------------------------------------------------------------------------------------------------------

  lazy val akkaDist = project("dist", "akka-dist", new AkkaDistParentProject(_))

  class AkkaDistParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akkaActorsDist = project("actors", "akka-dist-actors", new AkkaActorsDistProject(_), akka_actor)

    lazy val akkaCoreDist = project("core", "akka-dist-core", new AkkaCoreDistProject(_),
                                    akkaActorsDist, akka_remote, akka_http, akka_slf4j, akka_testkit, akka_actor_tests, akka_durable_mailboxes)

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
