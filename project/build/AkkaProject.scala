 /*---------------------------------------------------------------------------\
| Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se> |
\---------------------------------------------------------------------------*/

import com.weiglewilczek.bnd4sbt.BNDPlugin
import java.io.File
import java.util.jar.Attributes
import java.util.jar.Attributes.Name._
import sbt._
import sbt.CompileOrder._
import spde._

class AkkaParentProject(info: ProjectInfo) extends DefaultProject(info) {

  // -------------------------------------------------------------------------------------------------------------------
  // Compile settings
  // -------------------------------------------------------------------------------------------------------------------

  override def compileOptions = super.compileOptions ++
    Seq("-deprecation",
        "-Xmigration",
        "-Xcheckinit",
        "-Xstrict-warnings",
        "-Xwarninit",
        "-encoding", "utf8")
        .map(x => CompileOption(x))
  override def javaCompileOptions = JavaCompileOption("-Xlint:unchecked") :: super.javaCompileOptions.toList

  // -------------------------------------------------------------------------------------------------------------------
  // Deploy/dist settings
  // -------------------------------------------------------------------------------------------------------------------

  lazy val deployPath = info.projectPath / "deploy"
  lazy val distPath = info.projectPath / "dist"
  def distName = "%s_%s-%s.zip".format(name, buildScalaVersion, version)
  lazy val dist = zipTask(allArtifacts, "dist", distName) dependsOn (`package`) describedAs("Zips up the distribution.")

  // -------------------------------------------------------------------------------------------------------------------
  // All repositories *must* go here! See ModuleConigurations below.
  // -------------------------------------------------------------------------------------------------------------------

  object Repositories {
    lazy val AkkaRepo             = MavenRepository("Akka Repository", "http://scalablesolutions.se/akka/repository")
    lazy val CasbahRepo           = MavenRepository("Casbah Repo", "http://repo.bumnetworks.com/releases")
    lazy val CasbahSnapshotRepo   = MavenRepository("Casbah Snapshots", "http://repo.bumnetworks.com/snapshots")
    lazy val CodehausRepo         = MavenRepository("Codehaus Repo", "http://repository.codehaus.org")
    lazy val EmbeddedRepo         = MavenRepository("Embedded Repo", (info.projectPath / "embedded-repo").asURL.toString)
    lazy val FusesourceSnapshotRepo = MavenRepository("Fusesource Snapshots", "http://repo.fusesource.com/nexus/content/repositories/snapshots")
    lazy val GuiceyFruitRepo      = MavenRepository("GuiceyFruit Repo", "http://guiceyfruit.googlecode.com/svn/repo/releases/")
    lazy val JBossRepo            = MavenRepository("JBoss Repo", "https://repository.jboss.org/nexus/content/groups/public/")
    lazy val JavaNetRepo          = MavenRepository("java.net Repo", "http://download.java.net/maven/2")
    lazy val SonatypeSnapshotRepo = MavenRepository("Sonatype OSS Repo", "http://oss.sonatype.org/content/repositories/releases")
    lazy val SunJDMKRepo          = MavenRepository("Sun JDMK Repo", "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo")
    lazy val CasbahRepoReleases   = MavenRepository("Casbah Release Repo", "http://repo.bumnetworks.com/releases")
    lazy val ZookeeperRepo        = MavenRepository("Zookeeper Repo", "http://lilycms.org/maven/maven2/deploy/")
    lazy val ClojarsRepo          = MavenRepository("Clojars Repo", "http://clojars.org/repo")
  }

  // -------------------------------------------------------------------------------------------------------------------
  // ModuleConfigurations
  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // must be resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.
  // Therefore, if repositories are defined, this must happen as def, not as val.
  // -------------------------------------------------------------------------------------------------------------------

  import Repositories._
  lazy val atmosphereModuleConfig  = ModuleConfiguration("org.atmosphere", SonatypeSnapshotRepo)
  lazy val jettyModuleConfig       = ModuleConfiguration("org.eclipse.jetty", sbt.DefaultMavenRepository)
  lazy val guiceyFruitModuleConfig = ModuleConfiguration("org.guiceyfruit", GuiceyFruitRepo)
  // lazy val hawtdispatchModuleConfig  = ModuleConfiguration("org.fusesource.hawtdispatch", FusesourceSnapshotRepo)
  lazy val jbossModuleConfig       = ModuleConfiguration("org.jboss", JBossRepo)
  lazy val jdmkModuleConfig        = ModuleConfiguration("com.sun.jdmk", SunJDMKRepo)
  lazy val jmsModuleConfig         = ModuleConfiguration("javax.jms", SunJDMKRepo)
  lazy val jmxModuleConfig         = ModuleConfiguration("com.sun.jmx", SunJDMKRepo)
  lazy val jerseyContrModuleConfig = ModuleConfiguration("com.sun.jersey.contribs", JavaNetRepo)
  lazy val jerseyModuleConfig      = ModuleConfiguration("com.sun.jersey", JavaNetRepo)
  lazy val jgroupsModuleConfig     = ModuleConfiguration("jgroups", JBossRepo)
  lazy val multiverseModuleConfig  = ModuleConfiguration("org.multiverse", CodehausRepo)
  lazy val nettyModuleConfig       = ModuleConfiguration("org.jboss.netty", JBossRepo)
  lazy val scalaTestModuleConfig   = ModuleConfiguration("org.scalatest", ScalaToolsSnapshots)
  lazy val logbackModuleConfig     = ModuleConfiguration("ch.qos.logback",sbt.DefaultMavenRepository)
  lazy val atomikosModuleConfig    = ModuleConfiguration("com.atomikos",sbt.DefaultMavenRepository)
  lazy val casbahRelease           = ModuleConfiguration("com.novus",CasbahRepoReleases)
  lazy val zookeeperRelease        = ModuleConfiguration("org.apache.hadoop.zookeeper",ZookeeperRepo)
  lazy val casbahModuleConfig      = ModuleConfiguration("com.novus", CasbahRepo)
  lazy val timeModuleConfig        = ModuleConfiguration("org.scala-tools", "time", CasbahSnapshotRepo)
  lazy val voldemortModuleConfig   = ModuleConfiguration("voldemort", ClojarsRepo)
  lazy val embeddedRepo            = EmbeddedRepo // This is the only exception, because the embedded repo is fast!

  // -------------------------------------------------------------------------------------------------------------------
  // Versions
  // -------------------------------------------------------------------------------------------------------------------

  lazy val ATMO_VERSION          = "0.6.2"
  lazy val CAMEL_VERSION         = "2.4.0"
  lazy val CASSANDRA_VERSION     = "0.6.1"
  lazy val DISPATCH_VERSION      = "0.7.4"
  lazy val HAWT_DISPATCH_VERSION = "1.0"
  lazy val JACKSON_VERSION       = "1.2.1"
  lazy val JERSEY_VERSION        = "1.3"
  lazy val MULTIVERSE_VERSION    = "0.6.1"
  lazy val SCALATEST_VERSION     = "1.2-for-scala-2.8.0.final-SNAPSHOT"
  lazy val LOGBACK_VERSION       = "0.9.24"
  lazy val SLF4J_VERSION         = "1.6.0"
  lazy val SPRING_VERSION        = "3.0.3.RELEASE"
  lazy val ASPECTWERKZ_VERSION   = "2.2.1"
  lazy val JETTY_VERSION         = "7.1.4.v20100610"

  // -------------------------------------------------------------------------------------------------------------------
  // Dependencies
  // -------------------------------------------------------------------------------------------------------------------

  object Dependencies {

    // Compile

    lazy val annotation = "javax.annotation" % "jsr250-api" % "1.0" % "compile"

    lazy val aopalliance = "aopalliance" % "aopalliance" % "1.0" % "compile"

    lazy val atmo          = "org.atmosphere" % "atmosphere-annotations"     % ATMO_VERSION % "compile"
    lazy val atmo_jbossweb = "org.atmosphere" % "atmosphere-compat-jbossweb" % ATMO_VERSION % "compile"
    lazy val atmo_jersey   = "org.atmosphere" % "atmosphere-jersey"          % ATMO_VERSION % "compile"
    lazy val atmo_runtime  = "org.atmosphere" % "atmosphere-runtime"         % ATMO_VERSION % "compile"
    lazy val atmo_tomcat   = "org.atmosphere" % "atmosphere-compat-tomcat"   % ATMO_VERSION % "compile"
    lazy val atmo_weblogic = "org.atmosphere" % "atmosphere-compat-weblogic" % ATMO_VERSION % "compile"

    lazy val atomikos_transactions     = "com.atomikos" % "transactions"     % "3.2.3" % "compile"
    lazy val atomikos_transactions_api = "com.atomikos" % "transactions-api" % "3.2.3" % "compile"
    lazy val atomikos_transactions_jta = "com.atomikos" % "transactions-jta" % "3.2.3" % "compile"

    lazy val camel_core = "org.apache.camel" % "camel-core" % CAMEL_VERSION % "compile"

    lazy val cassandra = "org.apache.cassandra" % "cassandra" % CASSANDRA_VERSION % "compile"

    lazy val commons_codec = "commons-codec" % "commons-codec" % "1.4" % "compile"

    lazy val commons_io = "commons-io" % "commons-io" % "1.4" % "compile"

    lazy val commons_pool = "commons-pool" % "commons-pool" % "1.5.4" % "compile"

    lazy val configgy = "net.lag" % "configgy" % "2.8.0-1.5.5" % "compile"

    lazy val dispatch_http = "net.databinder" % "dispatch-http_2.8.0" % DISPATCH_VERSION % "compile"
    lazy val dispatch_json = "net.databinder" % "dispatch-json_2.8.0" % DISPATCH_VERSION % "compile"

    lazy val jetty         = "org.eclipse.jetty" % "jetty-server"  % JETTY_VERSION % "compile"
    lazy val jetty_util    = "org.eclipse.jetty" % "jetty-util"    % JETTY_VERSION % "compile"
    lazy val jetty_xml     = "org.eclipse.jetty" % "jetty-xml"     % JETTY_VERSION % "compile"
    lazy val jetty_servlet = "org.eclipse.jetty" % "jetty-servlet" % JETTY_VERSION % "compile"

    lazy val uuid       = "com.eaio" % "uuid" % "3.2" % "compile"

    lazy val guicey = "org.guiceyfruit" % "guice-all" % "2.0" % "compile"

    lazy val h2_lzf = "voldemort.store.compress" % "h2-lzf" % "1.0" % "compile"

    lazy val hawtdispatch = "org.fusesource.hawtdispatch" % "hawtdispatch-scala" % HAWT_DISPATCH_VERSION % "compile"

    lazy val jackson          = "org.codehaus.jackson" % "jackson-mapper-asl" % JACKSON_VERSION % "compile"
    lazy val jackson_core     = "org.codehaus.jackson" % "jackson-core-asl"   % JACKSON_VERSION % "compile"
    lazy val jackson_core_asl = "org.codehaus.jackson" % "jackson-core-asl"   % JACKSON_VERSION % "compile"

    lazy val jersey         = "com.sun.jersey"          % "jersey-core"   % JERSEY_VERSION % "compile"
    lazy val jersey_json    = "com.sun.jersey"          % "jersey-json"   % JERSEY_VERSION % "compile"
    lazy val jersey_server  = "com.sun.jersey"          % "jersey-server" % JERSEY_VERSION % "compile"
    lazy val jersey_contrib = "com.sun.jersey.contribs" % "jersey-scala"  % JERSEY_VERSION % "compile"

    lazy val jgroups = "jgroups" % "jgroups" % "2.9.0.GA" % "compile"

    lazy val jsr166x = "jsr166x" % "jsr166x" % "1.0" % "compile"

    lazy val jsr250 = "javax.annotation" % "jsr250-api" % "1.0" % "compile"

    lazy val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"

    lazy val jta_1_1 = "org.apache.geronimo.specs" % "geronimo-jta_1.1_spec" % "1.1.1" % "compile" intransitive

    lazy val mongo = "org.mongodb" % "mongo-java-driver" % "2.0" % "compile"

    lazy val casbah = "com.novus" % "casbah_2.8.0" % "1.0.8.5" % "compile"

    lazy val multiverse = "org.multiverse" % "multiverse-alpha" % MULTIVERSE_VERSION % "compile" intransitive

    lazy val netty = "org.jboss.netty" % "netty" % "3.2.2.Final" % "compile"

    lazy val protobuf = "com.google.protobuf" % "protobuf-java" % "2.3.0" % "compile"

    lazy val osgi_core = "org.osgi" % "org.osgi.core" % "4.2.0"

    lazy val rabbit = "com.rabbitmq" % "amqp-client" % "1.8.1" % "compile"

    lazy val redis = "com.redis" % "redisclient" % "2.8.0-2.0.1" % "compile"

    lazy val sbinary = "sbinary" % "sbinary" % "2.8.0-0.3.1" % "compile"

    lazy val sjson = "sjson.json" % "sjson" % "0.8-2.8.0" % "compile"
    lazy val sjson_test = "sjson.json" % "sjson" % "0.8-2.8.0" % "test"

    lazy val slf4j       = "org.slf4j" % "slf4j-api"     % SLF4J_VERSION % "compile"

    lazy val logback      = "ch.qos.logback" % "logback-classic" % LOGBACK_VERSION % "compile"
    lazy val logback_core = "ch.qos.logback" % "logback-core" % LOGBACK_VERSION % "compile"

    lazy val spring_beans   = "org.springframework" % "spring-beans"   % SPRING_VERSION % "compile"
    lazy val spring_context = "org.springframework" % "spring-context" % SPRING_VERSION % "compile"

    lazy val stax_api = "javax.xml.stream" % "stax-api" % "1.0-2" % "compile"

    lazy val thrift = "com.facebook" % "thrift" % "r917130" % "compile"

    lazy val voldemort = "voldemort" % "voldemort" % "0.81" % "compile"
    lazy val voldemort_contrib = "voldemort" % "voldemort-contrib" % "0.81" % "compile"
    lazy val voldemort_needs_log4j = "org.slf4j" % "log4j-over-slf4j" % SLF4J_VERSION % "compile"

    lazy val werkz      = "org.codehaus.aspectwerkz" % "aspectwerkz-nodeps-jdk5" % ASPECTWERKZ_VERSION % "compile"
    lazy val werkz_core = "org.codehaus.aspectwerkz" % "aspectwerkz-jdk5"        % ASPECTWERKZ_VERSION % "compile"

    lazy val zookeeper  = "org.apache.hadoop.zookeeper" % "zookeeper" % "3.2.2" % "compile"

    lazy val hadoop_core = "org.apache.hadoop" % "hadoop-core" % "0.20.2" % "compile"

    lazy val hbase_core = "org.apache.hbase" % "hbase-core" % "0.20.6" % "compile"

    // Test

    lazy val camel_spring   = "org.apache.camel"       % "camel-spring"        % CAMEL_VERSION     % "test"
    lazy val cassandra_clhm = "org.apache.cassandra"   % "clhm-production"     % CASSANDRA_VERSION % "test"
    lazy val commons_coll   = "commons-collections"    % "commons-collections" % "3.2.1"           % "test"
    lazy val google_coll    = "com.google.collections" % "google-collections"  % "1.0"             % "test"
    lazy val high_scale     = "org.apache.cassandra"   % "high-scale-lib"      % CASSANDRA_VERSION % "test"
    lazy val testJetty      = "org.eclipse.jetty"      % "jetty-server"        % JETTY_VERSION     % "test"
    lazy val testJettyWebApp= "org.eclipse.jetty"      % "jetty-webapp"        % JETTY_VERSION     % "test"

    lazy val junit          = "junit"                  % "junit"               % "4.5"             % "test"
    lazy val mockito        = "org.mockito"            % "mockito-all"         % "1.8.1"           % "test"
    lazy val scalatest      = "org.scalatest"          % "scalatest"           % SCALATEST_VERSION % "test"

    //HBase testing
    lazy val hadoop_test    = "org.apache.hadoop"      % "hadoop-test"         % "0.20.2"          % "test"
    lazy val hbase_test     = "org.apache.hbase"       % "hbase-test"          % "0.20.6"          % "test"
    lazy val log4j          = "log4j"                  % "log4j"               % "1.2.15"          % "test"
    lazy val jetty_mortbay  = "org.mortbay.jetty"      % "jetty"               % "6.1.14"          % "test"

    //voldemort testing
    lazy val jdom = "org.jdom" % "jdom" % "1.1" % "test"
    lazy val vold_jetty = "org.mortbay.jetty" % "jetty" % "6.1.18" % "test"
    lazy val velocity = "org.apache.velocity" % "velocity" % "1.6.2" % "test"
    lazy val dbcp = "commons-dbcp" % "commons-dbcp" % "1.2.2" % "test"
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Subprojects
  // -------------------------------------------------------------------------------------------------------------------

  lazy val akka_actor       = project("akka-actor", "akka-actor", new AkkaActorProject(_))
  lazy val akka_typed_actor = project("akka-typed-actor", "akka-typed-actor", new AkkaTypedActorProject(_), akka_actor)
  lazy val akka_remote      = project("akka-remote", "akka-remote", new AkkaRemoteProject(_), akka_typed_actor)
  lazy val akka_amqp        = project("akka-amqp", "akka-amqp", new AkkaAMQPProject(_), akka_remote)
  lazy val akka_http        = project("akka-http", "akka-http", new AkkaHttpProject(_), akka_remote, akka_camel)
  lazy val akka_camel       = project("akka-camel", "akka-camel", new AkkaCamelProject(_), akka_remote)
  lazy val akka_persistence = project("akka-persistence", "akka-persistence", new AkkaPersistenceParentProject(_))
  lazy val akka_spring      = project("akka-spring", "akka-spring", new AkkaSpringProject(_), akka_remote, akka_camel)
  lazy val akka_jta         = project("akka-jta", "akka-jta", new AkkaJTAProject(_), akka_remote)
  lazy val akka_kernel      = project("akka-kernel", "akka-kernel", new AkkaKernelProject(_),
                                       akka_remote, akka_jta, akka_http, akka_spring, akka_camel, akka_persistence, akka_amqp)
  lazy val akka_osgi        = project("akka-osgi", "akka-osgi", new AkkaOSGiParentProject(_))
  lazy val akka_samples     = project("akka-samples", "akka-samples", new AkkaSamplesParentProject(_))

  // -------------------------------------------------------------------------------------------------------------------
  // Miscellaneous
  // -------------------------------------------------------------------------------------------------------------------

  override def mainClass = Some("se.scalablesolutions.akka.kernel.Main")

  override def packageOptions =
    manifestClassPath.map(cp => ManifestAttributes(
      (Attributes.Name.CLASS_PATH, cp),
      (IMPLEMENTATION_TITLE, "Akka"),
      (IMPLEMENTATION_URL, "http://akkasource.org"),
      (IMPLEMENTATION_VENDOR, "The Akka Project")
    )).toList :::
    getMainClass(false).map(MainClass(_)).toList

  // create a manifest with all akka jars and dependency jars on classpath
  override def manifestClassPath = Some(allArtifacts.getFiles
    .filter(_.getName.endsWith(".jar"))
    .filter(!_.getName.contains("servlet_2.4"))
    .filter(!_.getName.contains("scala-library"))
    .map("lib_managed/scala_%s/compile/".format(buildScalaVersion) + _.getName)
    .mkString(" ") +
    " config/" +
    " scala-library.jar" +
    " dist/akka-actor_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-typed-actor_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-remote_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-http_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-camel_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-amqp_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-persistence-common_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-persistence-redis_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-persistence-mongo_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-persistence-cassandra_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-kernel_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-spring_%s-%s.jar".format(buildScalaVersion, version) +
    " dist/akka-jta_%s-%s.jar".format(buildScalaVersion, version)
    )

  //Exclude slf4j1.5.11 from the classpath, it's conflicting...
  override def fullClasspath(config: Configuration): PathFinder = {
    super.fullClasspath(config) ---
    (super.fullClasspath(config) ** "slf4j*1.5.11.jar")
  }

  override def mainResources = super.mainResources +++
          (info.projectPath / "config").descendentsExcept("*", "logback-test.xml")

  override def runClasspath = super.runClasspath +++ "config"

  // ------------------------------------------------------------
  // publishing
  override def managedStyle = ManagedStyle.Maven
  //override def defaultPublishRepository = Some(Resolver.file("maven-local", Path.userHome / ".m2" / "repository" asFile))
  val publishTo = Resolver.file("maven-local", Path.userHome / ".m2" / "repository" asFile)

  val sourceArtifact = Artifact(artifactID, "sources", "jar", Some("sources"), Nil, None)
  val docsArtifact = Artifact(artifactID, "docs", "jar", Some("docs"), Nil, None)

  // Credentials(Path.userHome / ".akka_publish_credentials", log)

  //override def documentOptions = encodingUtf8.map(SimpleDocOption(_))
  override def packageDocsJar = defaultJarPath("-docs.jar")
  override def packageSrcJar= defaultJarPath("-sources.jar")
  override def packageToPublishActions = super.packageToPublishActions ++ Seq(packageDocs, packageSrc)

  override def pomExtra =
    <inceptionYear>2009</inceptionYear>
    <url>http://akkasource.org</url>
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

  // publish to local mvn
  import Process._
  lazy val publishLocalMvn = runMvnInstall
  def runMvnInstall = task {
    for (absPath <- akkaArtifacts.getPaths) {
      val artifactRE = """(.*)/dist/(.*)-(.*).jar""".r
      val artifactRE(path, artifactId, artifactVersion) = absPath
      val command = "mvn install:install-file" +
                    " -Dfile=" + absPath +
                    " -DgroupId=se.scalablesolutions.akka" +
                    " -DartifactId=" + artifactId +
                    " -Dversion=" + version +
                    " -Dpackaging=jar -DgeneratePom=true"
      command ! log
    }
    None
  } dependsOn(dist) describedAs("Run mvn install for artifacts in dist.")

  // -------------------------------------------------------------------------------------------------------------------
  // akka-actor subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaActorProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val uuid          = Dependencies.uuid
    val configgy      = Dependencies.configgy
    val hawtdispatch  = Dependencies.hawtdispatch
    val multiverse    = Dependencies.multiverse
    val jsr166x       = Dependencies.jsr166x
    val slf4j         = Dependencies.slf4j
    val logback       = Dependencies.logback
    val logback_core  = Dependencies.logback_core

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-typed-actor subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTypedActorProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val aopalliance   = Dependencies.aopalliance
    val werkz         = Dependencies.werkz
    val werkz_core    = Dependencies.werkz_core
    val guicey        = Dependencies.guicey

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-remote subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val commons_codec = Dependencies.commons_codec
    val commons_io    = Dependencies.commons_io
    val dispatch_http = Dependencies.dispatch_http
    val dispatch_json = Dependencies.dispatch_json
    val guicey        = Dependencies.guicey
    val h2_lzf        = Dependencies.h2_lzf
    val jackson       = Dependencies.jackson
    val jackson_core  = Dependencies.jackson_core
    val jgroups       = Dependencies.jgroups
    val jta_1_1       = Dependencies.jta_1_1
    val netty         = Dependencies.netty
    val protobuf      = Dependencies.protobuf
    val sbinary       = Dependencies.sbinary
    val sjson         = Dependencies.sjson

    // testing
    val junit     = Dependencies.junit
    val scalatest = Dependencies.scalatest

    override def bndImportPackage = "javax.transaction;version=1.1" :: super.bndImportPackage.toList
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-amqp subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaAMQPProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val commons_io = Dependencies.commons_io
    val rabbit     = Dependencies.rabbit
    val protobuf      = Dependencies.protobuf

    // testing
    val junit      = Dependencies.junit
    val multiverse = Dependencies.multiverse
    val scalatest  = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-http subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaHttpProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val annotation       = Dependencies.annotation
    val atmo             = Dependencies.atmo
    val atmo_jbossweb    = Dependencies.atmo_jbossweb
    val atmo_jersey      = Dependencies.atmo_jersey
    val atmo_runtime     = Dependencies.atmo_runtime
    val atmo_tomcat      = Dependencies.atmo_tomcat
    val atmo_weblogic    = Dependencies.atmo_weblogic
    val jetty            = Dependencies.jetty
    val jetty_util       = Dependencies.jetty_util
    val jetty_xml        = Dependencies.jetty_xml
    val jetty_servlet    = Dependencies.jetty_servlet
    val jackson_core_asl = Dependencies.jackson_core_asl
    val jersey           = Dependencies.jersey
    val jersey_contrib   = Dependencies.jersey_contrib
    val jersey_json      = Dependencies.jersey_json
    val jersey_server    = Dependencies.jersey_server
    val jsr311           = Dependencies.jsr311
    val stax_api         = Dependencies.stax_api

    // testing
    val junit     = Dependencies.junit
    val mockito   = Dependencies.mockito
    val scalatest = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-camel subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val camel_core = Dependencies.camel_core
    
    override def testOptions = createTestFilter( _.endsWith("Test"))
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-persistence subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaPersistenceParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_persistence_common = project("akka-persistence-common", "akka-persistence-common",
      new AkkaPersistenceCommonProject(_), akka_remote)
    lazy val akka_persistence_redis = project("akka-persistence-redis", "akka-persistence-redis",
      new AkkaRedisProject(_), akka_persistence_common)
    lazy val akka_persistence_mongo = project("akka-persistence-mongo", "akka-persistence-mongo",
      new AkkaMongoProject(_), akka_persistence_common)
    lazy val akka_persistence_cassandra = project("akka-persistence-cassandra", "akka-persistence-cassandra",
      new AkkaCassandraProject(_), akka_persistence_common)
    lazy val akka_persistence_hbase = project("akka-persistence-hbase", "akka-persistence-hbase",
      new AkkaHbaseProject(_), akka_persistence_common)
    lazy val akka_persistence_voldemort = project("akka-persistence-voldemort", "akka-persistence-voldemort",
      new AkkaVoldemortProject(_), akka_persistence_common)
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-persistence-common subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaPersistenceCommonProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val commons_pool = Dependencies.commons_pool
    val thrift       = Dependencies.thrift
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-persistence-redis subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaRedisProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val commons_codec = Dependencies.commons_codec
    val redis         = Dependencies.redis

    override def testOptions = createTestFilter( _.endsWith("Test"))
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-persistence-mongo subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaMongoProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val mongo = Dependencies.mongo
    val casbah = Dependencies.casbah

    override def testOptions = createTestFilter( _.endsWith("Test"))
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-persistence-cassandra subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaCassandraProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val cassandra   = Dependencies.cassandra

    // testing
    val cassandra_clhm = Dependencies.cassandra_clhm
    val commons_coll   = Dependencies.commons_coll
    val google_coll    = Dependencies.google_coll
    val high_scale     = Dependencies.high_scale

    override def testOptions = createTestFilter( _.endsWith("Test"))
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-persistence-hbase subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaHbaseProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    override def ivyXML =
    <dependencies>
        <dependency org="org.apache.hadoop.zookeeper" name="zookeeper" rev="3.2.2" conf="compile">
        </dependency>
        <dependency org="org.apache.hadoop" name="hadoop-core" rev="0.20.2" conf="compile">
        </dependency>
        <dependency org="org.apache.hbase" name="hbase-core" rev="0.20.6" conf="compile">
        </dependency>

        <dependency org="org.apache.hadoop" name="hadoop-test" rev="0.20.2" conf="test">
	        <exclude module="slf4j-api"/>
        </dependency>
        <dependency org="org.slf4j" name="slf4j-api" rev={SLF4J_VERSION} conf="test">
        </dependency>
        <dependency org="org.apache.hbase" name="hbase-test" rev="0.20.6" conf="test">
        </dependency>
        <dependency org="log4j" name="log4j" rev="1.2.15" conf="test">
        </dependency>
        <dependency org="org.mortbay.jetty" name="jetty" rev="6.1.14" conf="test">
        </dependency>
    </dependencies>

    override def testOptions = createTestFilter( _.endsWith("Test") )
  }

  // akka-persistence-voldemort subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaVoldemortProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val voldemort = Dependencies.voldemort
    val voldemort_contrib = Dependencies.voldemort_contrib
    val voldemort_needs_log4j = Dependencies.voldemort_needs_log4j

    //testing
    val scalatest = Dependencies.scalatest
    val google_coll = Dependencies.google_coll
    val jdom = Dependencies.jdom
    val jetty = Dependencies.vold_jetty
    val velocity = Dependencies.velocity
    val dbcp = Dependencies.dbcp
    val sjson = Dependencies.sjson_test

    override def testOptions = createTestFilter( _.endsWith("Suite"))
  }


  // -------------------------------------------------------------------------------------------------------------------
  // akka-kernel subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaKernelProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath)

  // -------------------------------------------------------------------------------------------------------------------
  // akka-spring subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSpringProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val spring_beans   = Dependencies.spring_beans
    val spring_context = Dependencies.spring_context

    // testing
    val camel_spring = Dependencies.camel_spring
    val junit        = Dependencies.junit
    val scalatest    = Dependencies.scalatest
  }

  // -------------------------------------------------------------------------------------------------------------------
  // akka-jta subproject
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaJTAProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val atomikos_transactions     = Dependencies.atomikos_transactions
    val atomikos_transactions_api = Dependencies.atomikos_transactions_api
    val atomikos_transactions_jta = Dependencies.atomikos_transactions_jta
    //val jta_1_1                   = Dependencies.jta_1_1
    //val atomikos_transactions_util = "com.atomikos" % "transactions-util" % "3.2.3" % "compile"
  }

  // -------------------------------------------------------------------------------------------------------------------
  // OSGi stuff
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaOSGiParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_osgi_dependencies_bundle = project("akka-osgi-dependencies-bundle", "akka-osgi-dependencies-bundle",
      new AkkaOSGiDependenciesBundleProject(_), akka_kernel, akka_jta) // akka_kernel does not depend on akka_jta (why?) therefore we list akka_jta here
    lazy val akka_osgi_assembly = project("akka-osgi-assembly", "akka-osgi-assembly",
      new AkkaOSGiAssemblyProject(_), akka_osgi_dependencies_bundle, akka_remote, akka_amqp, akka_http,
        akka_camel, akka_spring, akka_jta, akka_persistence.akka_persistence_common,
        akka_persistence.akka_persistence_redis, akka_persistence.akka_persistence_mongo,
        akka_persistence.akka_persistence_cassandra,akka_persistence.akka_persistence_hbase,
      akka_persistence.akka_persistence_voldemort)
  }

  class AkkaOSGiDependenciesBundleProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with BNDPlugin {
    override def bndClasspath = compileClasspath
    override def bndPrivatePackage = Seq("")
    override def bndImportPackage = Seq("*;resolution:=optional")
    override def bndExportPackage = Seq(
      "org.aopalliance.*;version=1.0.0",

      // Provided by other bundles
      "!se.scalablesolutions.akka.*",
      "!com.google.inject.*",
      "!javax.transaction.*",
      "!javax.ws.rs.*",
      "!javax.jms.*",
      "!javax.transaction,*",
      "!org.apache.commons.io.*",
      "!org.apache.commons.pool.*",
      "!org.codehaus.jackson.*",
      "!org.jboss.netty.*",
      "!org.springframework.*",
      "!org.apache.camel.*",
      "!org.fusesource.commons.management.*",

      "*;version=0.0.0")
  }

  class AkkaOSGiAssemblyProject(info: ProjectInfo) extends DefaultProject(info) {

    // Scala bundle
    val scala_bundle = "com.weiglewilczek.scala-lang-osgi" % "scala-library" % buildScalaVersion % "compile" intransitive

    // Camel bundles
    val camel_core           = Dependencies.camel_core.intransitive
    val fusesource_commonman = "org.fusesource.commonman" % "commons-management" % "1.0" intransitive

    // Spring bundles
    val spring_beans      = Dependencies.spring_beans.intransitive
    val spring_context    = Dependencies.spring_context.intransitive
    val spring_aop        = "org.springframework" % "spring-aop"        % SPRING_VERSION % "compile" intransitive
    val spring_asm        = "org.springframework" % "spring-asm"        % SPRING_VERSION % "compile" intransitive
    val spring_core       = "org.springframework" % "spring-core"       % SPRING_VERSION % "compile" intransitive
    val spring_expression = "org.springframework" % "spring-expression" % SPRING_VERSION % "compile" intransitive
    val spring_jms        = "org.springframework" % "spring-jms"        % SPRING_VERSION % "compile" intransitive
    val spring_tx         = "org.springframework" % "spring-tx"         % SPRING_VERSION % "compile" intransitive

    val commons_codec      = Dependencies.commons_codec.intransitive
    val commons_io         = Dependencies.commons_io.intransitive
    val commons_pool       = Dependencies.commons_pool.intransitive
    val guicey             = Dependencies.guicey.intransitive
    val jackson            = Dependencies.jackson.intransitive
    val jackson_core       = Dependencies.jackson_core.intransitive
    val jsr311             = Dependencies.jsr311.intransitive
    val jta_1_1            = Dependencies.jta_1_1.intransitive
    val netty              = Dependencies.netty.intransitive
    val commons_fileupload = "commons-fileupload"        % "commons-fileupload" % "1.2.1" % "compile" intransitive
    val jms_1_1            = "org.apache.geronimo.specs" % "geronimo-jms_1.1_spec" % "1.1.1" % "compile" intransitive
    val joda               = "joda-time"                 % "joda-time" % "1.6" intransitive

    override def packageAction =
      task {
        val libs: Seq[Path] = managedClasspath(config("compile")).get.toSeq
        val prjs: Seq[Path] = info.dependencies.toSeq.asInstanceOf[Seq[DefaultProject]] map { _.jarPath }
        val all = libs ++ prjs
        val destination = outputPath / "bundles"
        FileUtilities.copyFlat(all, destination, log)
        log info "Copied %s bundles to %s".format(all.size, destination)
        None
      }

    override def artifacts = Set.empty
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Test
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaTypedActorTestProject(info: ProjectInfo) extends DefaultProject(info) {
    // testing
    val junit = "junit" % "junit" % "4.5" % "test"
    val jmock = "org.jmock" % "jmock" % "2.4.0" % "test"
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Examples
  // -------------------------------------------------------------------------------------------------------------------

  class AkkaSampleAntsProject(info: ProjectInfo) extends DefaultSpdeProject(info) {
    //val scalaToolsSnapshots = ScalaToolsSnapshots
    override def spdeSourcePath = mainSourcePath / "spde"
  }

  class AkkaSampleChatProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)
  class AkkaSamplePubSubProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)
  class AkkaSampleFSMProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleRestJavaProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleRestScalaProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val jsr311 = Dependencies.jsr311
  }

  class AkkaSampleCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    //Must be like this to be able to exclude the geronimo-servlet_2.4_spec which is a too old Servlet spec
    override def ivyXML =
      <dependencies>
        <dependency org="org.springframework" name="spring-jms" rev={SPRING_VERSION}>
        </dependency>
        <dependency org="org.apache.geronimo.specs" name="geronimo-servlet_2.5_spec" rev="1.1.1">
        </dependency>
        <dependency org="org.apache.camel" name="camel-jetty" rev="2.4.0.1">
          <exclude module="geronimo-servlet_2.4_spec"/>
        </dependency>
        <dependency org="org.apache.camel" name="camel-jms" rev={CAMEL_VERSION}>
        </dependency>
        <dependency org="org.apache.activemq" name="activemq-core" rev="5.3.2">
        </dependency>
      </dependencies>

    override def testOptions = createTestFilter( _.endsWith("Test"))
  }

  class AkkaSampleSecurityProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val commons_codec = Dependencies.commons_codec
    val jsr250        = Dependencies.jsr250
    val jsr311        = Dependencies.jsr311
  }

  class AkkaSampleOSGiProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with BNDPlugin {
    val osgi_core = Dependencies.osgi_core
    override lazy val bndBundleActivator = Some("se.scalablesolutions.akka.sample.osgi.Activator")
    override lazy val bndExportPackage = Nil // Necessary because of mixing-in AkkaDefaultProject which exports all ...akka.* packages!
  }

  class AkkaSamplesParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_sample_ants = project("akka-sample-ants", "akka-sample-ants",
      new AkkaSampleAntsProject(_), akka_remote)
    lazy val akka_sample_chat = project("akka-sample-chat", "akka-sample-chat",
      new AkkaSampleChatProject(_), akka_kernel)
    lazy val akka_sample_pubsub = project("akka-sample-pubsub", "akka-sample-pubsub",
      new AkkaSamplePubSubProject(_), akka_kernel)
    lazy val akka_sample_fsm = project("akka-sample-fsm", "akka-sample-fsm",
      new AkkaSampleFSMProject(_), akka_kernel)
    lazy val akka_sample_rest_java = project("akka-sample-rest-java", "akka-sample-rest-java",
      new AkkaSampleRestJavaProject(_), akka_kernel)
    lazy val akka_sample_rest_scala = project("akka-sample-rest-scala", "akka-sample-rest-scala",
      new AkkaSampleRestScalaProject(_), akka_kernel)
    lazy val akka_sample_camel = project("akka-sample-camel", "akka-sample-camel",
      new AkkaSampleCamelProject(_), akka_kernel)
    lazy val akka_sample_security = project("akka-sample-security", "akka-sample-security",
      new AkkaSampleSecurityProject(_), akka_kernel)
    lazy val akka_sample_remote = project("akka-sample-remote", "akka-sample-remote",
      new AkkaSampleRemoteProject(_), akka_kernel)
    lazy val akka_sample_osgi = project("akka-sample-osgi", "akka-sample-osgi",
      new AkkaSampleOSGiProject(_), akka_remote)
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------------------------------

  def removeDupEntries(paths: PathFinder) =
   Path.lazyPathFinder {
     val mapped = paths.get map { p => (p.relativePath, p) }
     (Map() ++ mapped).values.toList
   }

  def allArtifacts = {
    Path.fromFile(buildScalaInstance.libraryJar) +++
    (removeDupEntries(runClasspath filter ClasspathUtilities.isArchive) +++
    ((outputPath ##) / defaultJarName) +++
    mainResources +++
    mainDependencies.scalaJars +++
    descendents(info.projectPath / "scripts", "run_akka.sh") +++
    descendents(info.projectPath / "scripts", "akka-init-script.sh") +++
    descendents(info.projectPath / "dist", "*.jar") +++
    descendents(info.projectPath / "deploy", "*.jar") +++
    descendents(path("lib") ##, "*.jar") +++
    descendents(configurationPath(Configurations.Compile) ##, "*.jar"))
    .filter(jar => // remove redundant libs
      !jar.toString.endsWith("stax-api-1.0.1.jar") ||
      !jar.toString.endsWith("scala-library-2.7.7.jar")
    )
  }

  def akkaArtifacts = descendents(info.projectPath / "dist", "*" + buildScalaVersion  + "-" + version + ".jar")
  lazy val integrationTestsEnabled = systemOptional[Boolean]("integration.tests",false)
  lazy val stressTestsEnabled = systemOptional[Boolean]("stress.tests",false)

  // ------------------------------------------------------------
  class AkkaDefaultProject(info: ProjectInfo, val deployPath: Path) extends DefaultProject(info) with DeployProject with OSGiProject {
    lazy val sourceArtifact = Artifact(this.artifactID, "sources", "jar", Some("sources"), Nil, None)
    lazy val docsArtifact = Artifact(this.artifactID, "docs", "jar", Some("docs"), Nil, None)
    override def runClasspath = super.runClasspath +++ (AkkaParentProject.this.info.projectPath / "config")
    override def testClasspath = super.testClasspath +++ (AkkaParentProject.this.info.projectPath / "config")
    override def packageDocsJar = this.defaultJarPath("-docs.jar")
    override def packageSrcJar  = this.defaultJarPath("-sources.jar")
    override def packageToPublishActions = super.packageToPublishActions ++ Seq(this.packageDocs, this.packageSrc)

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
}

trait DeployProject { self: BasicScalaProject =>
  // defines where the deployTask copies jars to
  def deployPath: Path

  lazy val dist = deployTask(jarPath, packageDocsJar, packageSrcJar, deployPath, true, true, true) dependsOn(
    `package`, packageDocs, packageSrc) describedAs("Deploying")
  def deployTask(jar: Path, docs: Path, src: Path, toDir: Path,
                 genJar: Boolean, genDocs: Boolean, genSource: Boolean) = task {
    def gen(jar: Path, toDir: Path, flag: Boolean, msg: String): Option[String] =
    if (flag) {
      log.info(msg + " " + jar)
      FileUtilities.copyFile(jar, toDir / jar.name, log)
    } else None

    gen(jar, toDir, genJar, "Deploying bits") orElse
    gen(docs, toDir, genDocs, "Deploying docs") orElse
    gen(src, toDir, genSource, "Deploying sources")
  }
}

trait OSGiProject extends BNDPlugin { self: DefaultProject =>
  override def bndExportPackage = Seq("se.scalablesolutions.akka.*;version=%s".format(projectVersion.value))
}
