 /*---------------------------------------------------------------------------\
| Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se> |
\---------------------------------------------------------------------------*/

import sbt._
import sbt.CompileOrder._

import spde._
import de.tuxed.codefellow.plugin.CodeFellowPlugin

import java.util.jar.Attributes
import java.util.jar.Attributes.Name._
import java.io.File

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
	  lazy val CodehausSnapshotRepo = MavenRepository("Codehaus Snapshots", "http://snapshots.repository.codehaus.org")
	  lazy val EmbeddedRepo         = MavenRepository("Embedded Repo", (info.projectPath / "embedded-repo").asURL.toString)
	  lazy val GuiceyFruitRepo      = MavenRepository("GuiceyFruit Repo", "http://guiceyfruit.googlecode.com/svn/repo/releases/")
	  lazy val JBossRepo            = MavenRepository("JBoss Repo", "https://repository.jboss.org/nexus/content/groups/public/")
	  lazy val JavaNetRepo          = MavenRepository("java.net Repo", "http://download.java.net/maven/2")
	  lazy val SonatypeSnapshotRepo = MavenRepository("Sonatype OSS Repo", "http://oss.sonatype.org/content/repositories/releases")
	  lazy val SunJDMKRepo          = MavenRepository("Sun JDMK Repo", "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo")
	}

  // -------------------------------------------------------------------------------------------------------------------
  // ModuleConfigurations
  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // must be resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.
  // Therefore, if repositories are defined, this must happen as def, not as val.
  // -------------------------------------------------------------------------------------------------------------------
	import Repositories._
  lazy val atmosphereModuleConfig  = ModuleConfiguration("org.atmosphere", SonatypeSnapshotRepo)
  lazy val grizzlyModuleConfig     = ModuleConfiguration("com.sun.grizzly", JavaNetRepo)
  lazy val guiceyFruitModuleConfig = ModuleConfiguration("org.guiceyfruit", GuiceyFruitRepo)
  lazy val jbossModuleConfig       = ModuleConfiguration("org.jboss", JBossRepo)
  lazy val jdmkModuleConfig        = ModuleConfiguration("com.sun.jdmk", SunJDMKRepo)
  lazy val jerseyContrModuleConfig = ModuleConfiguration("com.sun.jersey.contribs", JavaNetRepo)
  lazy val jerseyModuleConfig      = ModuleConfiguration("com.sun.jersey", JavaNetRepo)
  lazy val jgroupsModuleConfig     = ModuleConfiguration("jgroups", JBossRepo)
  lazy val jmsModuleConfig         = ModuleConfiguration("javax.jms", SunJDMKRepo)
  lazy val jmxModuleConfig         = ModuleConfiguration("com.sun.jmx", SunJDMKRepo)
  lazy val liftModuleConfig        = ModuleConfiguration("net.liftweb", ScalaToolsSnapshots)
  lazy val multiverseModuleConfig  = ModuleConfiguration("org.multiverse", CodehausSnapshotRepo)
  lazy val nettyModuleConfig       = ModuleConfiguration("org.jboss.netty", JBossRepo)
  lazy val scalaTestModuleConfig   = ModuleConfiguration("org.scalatest", ScalaToolsSnapshots)

  // -------------------------------------------------------------------------------------------------------------------
  // Versions
  // -------------------------------------------------------------------------------------------------------------------
  lazy val ATMO_VERSION       = "0.6"
  lazy val CAMEL_VERSION      = "2.4.0"
  lazy val CASSANDRA_VERSION  = "0.6.1"
	lazy val DispatchVersion    = "0.7.4"
	lazy val JacksonVersion     = "1.2.1"
  lazy val JERSEY_VERSION     = "1.2"
  lazy val LIFT_VERSION       = "2.0-scala280-SNAPSHOT"
  lazy val MULTIVERSE_VERSION = "0.6-SNAPSHOT"
  lazy val SCALATEST_VERSION  = "1.2-for-scala-2.8.0.final-SNAPSHOT"
	lazy val Slf4jVersion       = "1.6.0"
  lazy val SPRING_VERSION     = "3.0.3.RELEASE"
	lazy val WerkzVersion       = "2.2.1"

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

    lazy val commons_logging = "commons-logging" % "commons-logging" % "1.1.1" % "compile"

    lazy val commons_pool = "commons-pool" % "commons-pool" % "1.5.4" % "compile"

    lazy val configgy = "net.lag" % "configgy" % "2.8.0-1.5.5" % "compile"

    lazy val dispatch_http = "net.databinder" % "dispatch-http_2.8.0" % DispatchVersion % "compile"
    lazy val dispatch_json = "net.databinder" % "dispatch-json_2.8.0" % DispatchVersion % "compile"

    lazy val grizzly = "com.sun.grizzly" % "grizzly-comet-webserver" % "1.9.18-i" % "compile"

    lazy val guicey = "org.guiceyfruit" % "guice-all" % "2.0" % "compile"

    lazy val h2_lzf = "voldemort.store.compress" % "h2-lzf" % "1.0" % "compile"

    lazy val jackson          = "org.codehaus.jackson" % "jackson-mapper-asl" % JacksonVersion % "compile"
    lazy val jackson_core     = "org.codehaus.jackson" % "jackson-core-asl"   % JacksonVersion % "compile"
    lazy val jackson_core_asl = "org.codehaus.jackson" % "jackson-core-asl"   % JacksonVersion % "compile"

    lazy val jersey         = "com.sun.jersey"          % "jersey-core"   % JERSEY_VERSION % "compile"
    lazy val jersey_json    = "com.sun.jersey"          % "jersey-json"   % JERSEY_VERSION % "compile"
    lazy val jersey_server  = "com.sun.jersey"          % "jersey-server" % JERSEY_VERSION % "compile"
    lazy val jersey_contrib = "com.sun.jersey.contribs" % "jersey-scala"  % JERSEY_VERSION % "compile"

    lazy val jgroups = "jgroups" % "jgroups" % "2.9.0.GA" % "compile"

    lazy val jsr166x = "jsr166x" % "jsr166x" % "1.0" % "compile"

    lazy val jsr250 = "javax.annotation" % "jsr250-api" % "1.0" % "compile"

    lazy val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"

    lazy val jta_1_1 = "org.apache.geronimo.specs" % "geronimo-jta_1.1_spec" % "1.1.1" % "compile" intransitive()

    lazy val lift      = "net.liftweb" % "lift-webkit" % LIFT_VERSION % "compile"
    lazy val lift_util = "net.liftweb" % "lift-util"   % LIFT_VERSION % "compile"

    lazy val log4j = "log4j" % "log4j" % "1.2.15" % "compile"

    lazy val mongo = "org.mongodb" % "mongo-java-driver" % "1.4" % "compile"

    lazy val multiverse = "org.multiverse" % "multiverse-alpha" % MULTIVERSE_VERSION % "compile" intransitive()

    lazy val netty = "org.jboss.netty" % "netty" % "3.2.1.Final" % "compile"

    lazy val protobuf = "com.google.protobuf" % "protobuf-java" % "2.3.0" % "compile"

    lazy val rabbit = "com.rabbitmq" % "amqp-client" % "1.8.1" % "compile"

    lazy val redis = "com.redis" % "redisclient" % "2.8.0-1.4" % "compile"

    lazy val sbinary = "sbinary" % "sbinary" % "2.8.0-0.3.1" % "compile"

    lazy val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"

    lazy val sjson = "sjson.json" % "sjson" % "0.7-SNAPSHOT-2.8.0" % "compile"

    lazy val slf4j       = "org.slf4j" % "slf4j-api"     % Slf4jVersion % "compile"
    lazy val slf4j_log4j = "org.slf4j" % "slf4j-log4j12" % Slf4jVersion % "compile"

    lazy val spring_beans   = "org.springframework" % "spring-beans"   % SPRING_VERSION % "compile"
    lazy val spring_context = "org.springframework" % "spring-context" % SPRING_VERSION % "compile"

    lazy val stax_api = "javax.xml.stream" % "stax-api" % "1.0-2" % "compile"

    lazy val thrift = "com.facebook" % "thrift" % "r917130" % "compile"

    lazy val werkz      = "org.codehaus.aspectwerkz" % "aspectwerkz-nodeps-jdk5" % WerkzVersion % "compile"
    lazy val werkz_core = "org.codehaus.aspectwerkz" % "aspectwerkz-jdk5"        % WerkzVersion % "compile"

    // Test

    lazy val camel_spring   = "org.apache.camel"       % "camel-spring"        % CAMEL_VERSION     % "test"
    lazy val cassandra_clhm = "org.apache.cassandra"   % "clhm-production"     % CASSANDRA_VERSION % "test"
    lazy val commons_coll   = "commons-collections"    % "commons-collections" % "3.2.1"           % "test"
    lazy val google_coll    = "com.google.collections" % "google-collections"  % "1.0"             % "test"
    lazy val high_scale     = "org.apache.cassandra"   % "high-scale-lib"      % CASSANDRA_VERSION % "test"
    lazy val jetty          = "org.mortbay.jetty"      % "jetty"               % "6.1.22"          % "test"
    lazy val junit          = "junit"                  % "junit"               % "4.5"             % "test"
    lazy val mockito        = "org.mockito"            % "mockito-all"         % "1.8.1"           % "test"
    lazy val scalatest      = "org.scalatest"          % "scalatest"           % SCALATEST_VERSION % "test"
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Subprojects
  // -------------------------------------------------------------------------------------------------------------------
  lazy val akka_core        = project("akka-core", "akka-core", new AkkaCoreProject(_))
  lazy val akka_amqp        = project("akka-amqp", "akka-amqp", new AkkaAMQPProject(_), akka_core)
  lazy val akka_http        = project("akka-http", "akka-http", new AkkaHttpProject(_), akka_core, akka_camel)
  lazy val akka_camel       = project("akka-camel", "akka-camel", new AkkaCamelProject(_), akka_core)
  lazy val akka_persistence = project("akka-persistence", "akka-persistence", new AkkaPersistenceParentProject(_))
  lazy val akka_spring      = project("akka-spring", "akka-spring", new AkkaSpringProject(_), akka_core, akka_camel)
  lazy val akka_jta         = project("akka-jta", "akka-jta", new AkkaJTAProject(_), akka_core)
  lazy val akka_kernel      = project("akka-kernel", "akka-kernel", new AkkaKernelProject(_),
                                      akka_core, akka_http, akka_spring, akka_camel, akka_persistence, akka_amqp)
  lazy val akka_samples     = project("akka-samples", "akka-samples", new AkkaSamplesParentProject(_))

  // ------------------------------------------------------------
  // Run Akka microkernel using 'sbt run' + use for packaging executable JAR
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
    " scala-library.jar" +
    " dist/akka-core_%s-%s.jar".format(buildScalaVersion, version) +
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

  // Exclude slf4j1.5.11 from the classpath, it's conflicting...
  override def runClasspath = super.runClasspath --- (super.runClasspath ** "slf4j*1.5.11.jar")

  // ------------------------------------------------------------
  // publishing
  override def managedStyle = ManagedStyle.Maven
  //override def defaultPublishRepository = Some(Resolver.file("maven-local", Path.userHome / ".m2" / "repository" asFile))
  val publishTo = Resolver.file("maven-local", Path.userHome / ".m2" / "repository" asFile)

  val sourceArtifact = Artifact(artifactID, "source", "jar", Some("source"), Nil, None)
  val docsArtifact = Artifact(artifactID, "docs", "jar", Some("docs"), Nil, None)

  // Credentials(Path.userHome / ".akka_publish_credentials", log)

  //override def documentOptions = encodingUtf8.map(SimpleDocOption(_))
  override def packageDocsJar = defaultJarPath("-docs.jar")
  override def packageSrcJar= defaultJarPath("-source.jar")
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
	// akka-core subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaCoreProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with CodeFellowPlugin {
    val netty         = Dependencies.netty
    val commons_codec = Dependencies.commons_codec
    val commons_io    = Dependencies.commons_io
    val dispatch_json = Dependencies.dispatch_json
    val dispatch_http = Dependencies.dispatch_http
    val sjson         = Dependencies.sjson
    val sbinary       = Dependencies.sbinary
    val jackson       = Dependencies.jackson
    val jackson_core  = Dependencies.jackson_core
    val h2_lzf        = Dependencies.h2_lzf
    val jsr166x       = Dependencies.jsr166x
    val jta_1_1       = Dependencies.jta_1_1
    val werkz         = Dependencies.werkz
    val werkz_core    = Dependencies.werkz_core
    val configgy      = Dependencies.configgy
    val guicey        = Dependencies.guicey
    val aopalliance   = Dependencies.aopalliance
    val protobuf      = Dependencies.protobuf
    val multiverse    = Dependencies.multiverse
    val jgroups       = Dependencies.jgroups

    // testing
    val scalatest = Dependencies.scalatest
    val junit     = Dependencies.junit
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-amqp subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaAMQPProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with CodeFellowPlugin {
    val commons_io = "commons-io" % "commons-io" % "1.4" % "compile"
    val rabbit = "com.rabbitmq" % "amqp-client" % "1.8.1" % "compile"

    // testing
    val multiverse = "org.multiverse" % "multiverse-alpha" % MULTIVERSE_VERSION % "test" intransitive()
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-http subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaHttpProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with CodeFellowPlugin {
    val jackson_core_asl = "org.codehaus.jackson" % "jackson-core-asl" % "1.2.1" % "compile"
    val stax_api = "javax.xml.stream" % "stax-api" % "1.0-2" % "compile"
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"
    val jersey = "com.sun.jersey" % "jersey-core" % JERSEY_VERSION % "compile"
    val jersey_server = "com.sun.jersey" % "jersey-server" % JERSEY_VERSION % "compile"
    val jersey_json = "com.sun.jersey" % "jersey-json" % JERSEY_VERSION % "compile"
    val jersey_contrib = "com.sun.jersey.contribs" % "jersey-scala" % JERSEY_VERSION % "compile"
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile"
    val grizzly = "com.sun.grizzly" % "grizzly-comet-webserver" % "1.9.18-i" % "compile"
    val atmo = "org.atmosphere" % "atmosphere-annotations" % ATMO_VERSION % "compile"
    val atmo_jersey = "org.atmosphere" % "atmosphere-jersey" % ATMO_VERSION % "compile"
    val atmo_runtime = "org.atmosphere" % "atmosphere-runtime" % ATMO_VERSION % "compile"
    val atmo_tomcat = "org.atmosphere" % "atmosphere-compat-tomcat" % ATMO_VERSION % "compile"
    val atmo_weblogic = "org.atmosphere" % "atmosphere-compat-weblogic" % ATMO_VERSION % "compile"
    val atmo_jbossweb = "org.atmosphere" % "atmosphere-compat-jbossweb" % ATMO_VERSION % "compile"
    val commons_logging = "commons-logging" % "commons-logging" % "1.1.1" % "compile"
    val annotation = "javax.annotation" % "jsr250-api" % "1.0" % "compile"

    // testing
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    val mockito = "org.mockito" % "mockito-all" % "1.8.1" % "test"
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-camel subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with CodeFellowPlugin {
    val camel_core = "org.apache.camel" % "camel-core" % CAMEL_VERSION % "compile"
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-persistence subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaPersistenceParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_persistence_common = project("akka-persistence-common", "akka-persistence-common",
      new AkkaPersistenceCommonProject(_), akka_core)
    lazy val akka_persistence_redis = project("akka-persistence-redis", "akka-persistence-redis",
      new AkkaRedisProject(_), akka_persistence_common)
    lazy val akka_persistence_mongo = project("akka-persistence-mongo", "akka-persistence-mongo",
      new AkkaMongoProject(_), akka_persistence_common)
    lazy val akka_persistence_cassandra = project("akka-persistence-cassandra", "akka-persistence-cassandra",
      new AkkaCassandraProject(_), akka_persistence_common)
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-persistence-common subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaPersistenceCommonProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val thrift = "com.facebook" % "thrift" % "r917130" % "compile"
    val commons_pool = "commons-pool" % "commons-pool" % "1.5.4" % "compile"
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-persistence-redis subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaRedisProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val redis = "com.redis" % "redisclient" % "2.8.0-1.4" % "compile"
    val commons_codec = "commons-codec" % "commons-codec" % "1.4" % "compile"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-persistence-mongo subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaMongoProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val mongo = "org.mongodb" % "mongo-java-driver" % "1.4" % "compile"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-persistence-cassandra subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaCassandraProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val cassandra = "org.apache.cassandra" % "cassandra" % CASSANDRA_VERSION % "compile"
    val slf4j = "org.slf4j" % "slf4j-api" % "1.6.0" % "compile"
    val slf4j_log4j = "org.slf4j" % "slf4j-log4j12" % "1.6.0" % "compile"
    val log4j = "log4j" % "log4j" % "1.2.15" % "compile"
    // testing
    val high_scale = "org.apache.cassandra" % "high-scale-lib" % CASSANDRA_VERSION % "test"
    val cassandra_clhm = "org.apache.cassandra" % "clhm-production" % CASSANDRA_VERSION % "test"
    val commons_coll = "commons-collections" % "commons-collections" % "3.2.1" % "test"
    val google_coll = "com.google.collections" % "google-collections" % "1.0" % "test"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-kernel subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaKernelProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath)

	// -------------------------------------------------------------------------------------------------------------------
	// akka-spring subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaSpringProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with CodeFellowPlugin {
    val spring_beans = "org.springframework" % "spring-beans" % SPRING_VERSION % "compile"
    val spring_context = "org.springframework" % "spring-context" % SPRING_VERSION % "compile"

    // testing
    val camel_spring = "org.apache.camel" % "camel-spring" % CAMEL_VERSION % "test"
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

	// -------------------------------------------------------------------------------------------------------------------
	// akka-jta subproject
	// -------------------------------------------------------------------------------------------------------------------
  class AkkaJTAProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with CodeFellowPlugin {
    val atomikos_transactions = "com.atomikos" % "transactions" % "3.2.3" % "compile"
    val atomikos_transactions_jta = "com.atomikos" % "transactions-jta" % "3.2.3" % "compile"
    val atomikos_transactions_api = "com.atomikos" % "transactions-api" % "3.2.3" % "compile"
    //val atomikos_transactions_util = "com.atomikos" % "transactions-util" % "3.2.3" % "compile"
    val jta_spec = "org.apache.geronimo.specs" % "geronimo-jta_1.1_spec" % "1.1.1" % "compile" intransitive()
  }

  // ================= EXAMPLES ==================
  class AkkaSampleAntsProject(info: ProjectInfo) extends DefaultSpdeProject(info) with CodeFellowPlugin {
//    val scalaToolsSnapshots = ScalaToolsSnapshots
    override def spdeSourcePath = mainSourcePath / "spde"
  }

  class AkkaSampleChatProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) with CodeFellowPlugin
  class AkkaSamplePubSubProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) with CodeFellowPlugin

  class AkkaSampleLiftProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) with CodeFellowPlugin {
    val commons_logging = "commons-logging" % "commons-logging" % "1.1.1" % "compile"
    val lift = "net.liftweb" % "lift-webkit" % LIFT_VERSION % "compile"
    val lift_util = "net.liftweb" % "lift-util" % LIFT_VERSION % "compile"
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"
    // testing
    val jetty = "org.mortbay.jetty" % "jetty" % "6.1.22" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaSampleRestJavaProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) with CodeFellowPlugin

  class AkkaSampleRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) with CodeFellowPlugin

  class AkkaSampleRestScalaProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) with CodeFellowPlugin {
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1.1" % "compile"
  }

  class AkkaSampleCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) with CodeFellowPlugin {
    override def ivyXML =
      <dependencies>
        <dependency org="org.springframework" name="spring-jms" rev={SPRING_VERSION}>
        </dependency>
        <dependency org="org.apache.geronimo.specs" name="geronimo-servlet_2.5_spec" rev="1.1.1">
        </dependency>
        <dependency org="org.apache.camel" name="camel-jetty" rev={CAMEL_VERSION}>
          <exclude module="geronimo-servlet_2.4_spec"/>
        </dependency>
        <dependency org="org.apache.camel" name="camel-jms" rev={CAMEL_VERSION}>
        </dependency>
        <dependency org="org.apache.activemq" name="activemq-core" rev="5.3.2">
        </dependency>
      </dependencies>
  }

  class AkkaSampleSecurityProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) with CodeFellowPlugin {
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1.1" % "compile"
    val jsr250 = "javax.annotation" % "jsr250-api" % "1.0" % "compile"
    val commons_codec = "commons-codec" % "commons-codec" % "1.4" % "compile"
  }

  class AkkaSamplesParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_sample_ants = project("akka-sample-ants", "akka-sample-ants",
      new AkkaSampleAntsProject(_), akka_core)
    lazy val akka_sample_chat = project("akka-sample-chat", "akka-sample-chat",
      new AkkaSampleChatProject(_), akka_kernel)
    lazy val akka_sample_pubsub = project("akka-sample-pubsub", "akka-sample-pubsub",
      new AkkaSamplePubSubProject(_), akka_kernel)
    lazy val akka_sample_lift = project("akka-sample-lift", "akka-sample-lift",
      new AkkaSampleLiftProject(_), akka_kernel)
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
  }

  // ------------------------------------------------------------
  // helper functions
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
    descendents(info.projectPath, "*.conf") +++
    descendents(info.projectPath / "scripts", "run_akka.sh") +++
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

  // ------------------------------------------------------------
  class AkkaDefaultProject(info: ProjectInfo, val deployPath: Path) extends DefaultProject(info) with DeployProject

  trait DeployProject extends DefaultProject {
    // defines where the deployTask copies jars to
    def deployPath: Path

    lazy val dist = distAction
    def distAction = deployTask(jarPath, packageDocsJar, packageSrcJar, deployPath, true, true, true) dependsOn(
      `package`, packageDocs, packageSrc) describedAs("Deploying")
    def deployTask(jar: Path, docs: Path, src: Path, toDir: Path,
                   genJar: Boolean, genDocs: Boolean, genSource: Boolean) = task {
      gen(jar, toDir, genJar, "Deploying bits") orElse
      gen(docs, toDir, genDocs, "Deploying docs") orElse
      gen(src, toDir, genSource, "Deploying sources")
    }
    private def gen(jar: Path, toDir: Path, flag: Boolean, msg: String): Option[String] =
      if (flag) {
        log.info(msg + " " + jar)
        FileUtilities.copyFile(jar, toDir / jar.name, log)
      } else None
  }
}
