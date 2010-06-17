 /*---------------------------------------------------------------------------\
| Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se> |
\---------------------------------------------------------------------------*/

import sbt._
import sbt.CompileOrder._
import spde._

import java.util.jar.Attributes
import java.util.jar.Attributes.Name._
import java.io.File

import com.weiglewilczek.bnd4sbt.BNDPlugin

class AkkaParent(info: ProjectInfo) extends DefaultProject(info) {

  // ------------------------------------------------------------
  // project versions
  val JERSEY_VERSION = "1.2"
  val ATMO_VERSION = "0.6-20100604"
  val CASSANDRA_VERSION = "0.6.1"
  val LIFT_VERSION = "2.0-scala280-SNAPSHOT"
  val SCALATEST_VERSION = "1.2-for-scala-2.8.0.RC3-SNAPSHOT"
  val MULTIVERSE_VERSION = "0.5.2"

  // ------------------------------------------------------------
  lazy val deployPath = info.projectPath / "deploy"
  lazy val distPath = info.projectPath / "dist"

  override def compileOptions = super.compileOptions ++
    Seq("-deprecation",
        "-Xmigration",
        "-Xcheckinit",
        "-Xstrict-warnings",
        "-Xwarninit",
        "-encoding", "utf8")
        .map(x => CompileOption(x))

  override def javaCompileOptions = JavaCompileOption("-Xlint:unchecked") :: super.javaCompileOptions.toList

  def distName = "%s_%s-%s.zip".format(name, buildScalaVersion, version)

  lazy val dist = zipTask(allArtifacts, "dist", distName) dependsOn (`package`) describedAs("Zips up the distribution.")

  // -------------------------------------------------------------------------------------------------------------------
  // Repositories
  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // must be resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.
  // Therefore, if repositories are defined, this must happen as def, not as val.
  // -------------------------------------------------------------------------------------------------------------------
  val embeddedRepo            = "Embedded Repo" at (info.projectPath / "embedded-repo").asURL.toString
  val scalaTestModuleConfig   = ModuleConfiguration("org.scalatest", ScalaToolsSnapshots)
  def guiceyFruitRepo         = "GuiceyFruit Repo" at "http://guiceyfruit.googlecode.com/svn/repo/releases/"
  val guiceyFruitModuleConfig = ModuleConfiguration("org.guiceyfruit", guiceyFruitRepo)
  def jbossRepo               = "JBoss Repo" at "https://repository.jboss.org/nexus/content/groups/public/"
  val jbossModuleConfig       = ModuleConfiguration("org.jboss", jbossRepo)
  val nettyModuleConfig       = ModuleConfiguration("org.jboss.netty", jbossRepo)
  val jgroupsModuleConfig     = ModuleConfiguration("jgroups", jbossRepo)
  def sunjdmkRepo             = "Sun JDMK Repo" at "http://wp5.e-taxonomy.eu/cdmlib/mavenrepo"
  val jmsModuleConfig         = ModuleConfiguration("javax.jms", sunjdmkRepo)
  val jdmkModuleConfig        = ModuleConfiguration("com.sun.jdmk", sunjdmkRepo)
  val jmxModuleConfig         = ModuleConfiguration("com.sun.jmx", sunjdmkRepo)
  def javaNetRepo             = "java.net Repo" at "http://download.java.net/maven/2"
  def sonatypeSnapshotRepo    = "Sonatype OSS Repo" at "http://oss.sonatype.org/content/repositories/snapshots"
  val jerseyModuleConfig      = ModuleConfiguration("com.sun.jersey", javaNetRepo)
  val jerseyContrModuleConfig = ModuleConfiguration("com.sun.jersey.contribs", javaNetRepo)
  val grizzlyModuleConfig     = ModuleConfiguration("com.sun.grizzly", javaNetRepo)
 // val atmosphereModuleConfig  = ModuleConfiguration("org.atmosphere", sonatypeSnapshotRepo)
  val liftModuleConfig        = ModuleConfiguration("net.liftweb", ScalaToolsSnapshots)
  val scalaBundleConfig       = ModuleConfiguration("com.weiglewilczek.scala-lang-osgi", ScalaToolsReleases)

  // ------------------------------------------------------------
  // project defintions
  lazy val akka_core = project("akka-core", "akka-core", new AkkaCoreProject(_))
  lazy val akka_amqp = project("akka-amqp", "akka-amqp", new AkkaAMQPProject(_), akka_core)
  lazy val akka_http = project("akka-http", "akka-http", new AkkaHttpProject(_), akka_core, akka_camel)
  lazy val akka_camel = project("akka-camel", "akka-camel", new AkkaCamelProject(_), akka_core)
  lazy val akka_persistence = project("akka-persistence", "akka-persistence", new AkkaPersistenceParentProject(_))
  lazy val akka_spring = project("akka-spring", "akka-spring", new AkkaSpringProject(_), akka_core)
  lazy val akka_jta = project("akka-jta", "akka-jta", new AkkaJTAProject(_), akka_core)
  lazy val akka_kernel = project("akka-kernel", "akka-kernel", new AkkaKernelProject(_),
    akka_core, akka_http, akka_spring, akka_camel, akka_persistence, akka_amqp)
  lazy val akka_osgi = project("akka-osgi", "akka-osgi", new AkkaOSGiParentProject(_))

  // active object tests in java
  lazy val akka_active_object_test = project("akka-active-object-test", "akka-active-object-test", new AkkaActiveObjectTestProject(_), akka_kernel)

  // examples
  lazy val akka_samples = project("akka-samples", "akka-samples", new AkkaSamplesParentProject(_))

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

  //Exclude slf4j1.5.11 from the classpath, it's conflicting...
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

  // ------------------------------------------------------------
  // subprojects
  class AkkaCoreProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val netty = "org.jboss.netty" % "netty" % "3.2.0.CR1" % "compile"
    val commons_io = "commons-io" % "commons-io" % "1.4" % "compile"
    val dispatch_json = "net.databinder" % "dispatch-json_2.8.0.RC3" % "0.7.4" % "compile"
    val dispatch_htdisttp = "net.databinder" % "dispatch-http_2.8.0.RC3" % "0.7.4" % "compile"
    val sjson = "sjson.json" % "sjson" % "0.6-SNAPSHOT-2.8.RC3" % "compile"
    val sbinary = "sbinary" % "sbinary" % "2.8.0.RC3-0.3.1-SNAPSHOT" % "compile"
    val jackson = "org.codehaus.jackson" % "jackson-mapper-asl" % "1.2.1" % "compile"
    val jackson_core = "org.codehaus.jackson" % "jackson-core-asl" % "1.2.1" % "compile"
    val h2_lzf = "voldemort.store.compress" % "h2-lzf" % "1.0" % "compile"
    val jsr166x = "jsr166x" % "jsr166x" % "1.0" % "compile"
    val jta_1_1 = "org.apache.geronimo.specs" % "geronimo-jta_1.1_spec" % "1.1.1" % "compile"
    val werkz = "org.codehaus.aspectwerkz" % "aspectwerkz-nodeps-jdk5" % "2.1" % "compile"
    val werkz_core = "org.codehaus.aspectwerkz" % "aspectwerkz-jdk5" % "2.1" % "compile"
    val configgy = "net.lag" % "configgy" % "2.8.0.RC3-1.5.2-SNAPSHOT" % "compile"
    val guicey = "org.guiceyfruit" % "guice-all" % "2.0" % "compile"
    val aopalliance = "aopalliance" % "aopalliance" % "1.0" % "compile"
    val protobuf = "com.google.protobuf" % "protobuf-java" % "2.3.0" % "compile"
    val multiverse = "org.multiverse" % "multiverse-alpha" % MULTIVERSE_VERSION % "compile" intransitive()
    val jgroups = "jgroups" % "jgroups" % "2.9.0.GA" % "compile"

    // testing
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaAMQPProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val commons_io = "commons-io" % "commons-io" % "1.4" % "compile"
    val rabbit = "com.rabbitmq" % "amqp-client" % "1.7.2" % "compile"
  }

  class AkkaHttpProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
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
    val lift_common = "net.liftweb" % "lift-common" % LIFT_VERSION % "compile"
    val lift_util = "net.liftweb" % "lift-util" % LIFT_VERSION % "compile"

    // testing
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
    val mockito = "org.mockito" % "mockito-all" % "1.8.1" % "test"
  }

  class AkkaCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val camel_core = "org.apache.camel" % "camel-core" % "2.3.0" % "compile"
  }

  class AkkaPersistenceCommonProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val thrift = "com.facebook" % "thrift" % "r917130" % "compile"
    val commons_pool = "commons-pool" % "commons-pool" % "1.5.4" % "compile"
  }

  class AkkaRedisProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val redis = "com.redis" % "redisclient" % "2.8.0.RC3-1.4-SNAPSHOT" % "compile"
    val commons_codec = "commons-codec" % "commons-codec" % "1.4" % "compile"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
  }

  class AkkaMongoProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val mongo = "org.mongodb" % "mongo-java-driver" % "1.4" % "compile"
    override def testOptions = TestFilter((name: String) => name.endsWith("Test")) :: Nil
  }

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

  class AkkaKernelProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath)

  class AkkaSpringProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val spring_beans = "org.springframework" % "spring-beans" % "3.0.1.RELEASE" % "compile"
    val spring_context = "org.springframework" % "spring-context" % "3.0.1.RELEASE" % "compile"

    // testing
    val scalatest = "org.scalatest" % "scalatest" % SCALATEST_VERSION % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaJTAProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) {
    val atomikos_transactions = "com.atomikos" % "transactions" % "3.2.3" % "compile"
    val atomikos_transactions_jta = "com.atomikos" % "transactions-jta" % "3.2.3" % "compile"
    val atomikos_transactions_api = "com.atomikos" % "transactions-api" % "3.2.3" % "compile"
    //val atomikos_transactions_util = "com.atomikos" % "transactions-util" % "3.2.3" % "compile"
    val jta_spec = "org.apache.geronimo.specs" % "geronimo-jta_1.1_spec" % "1.1.1" % "compile"
  }

  // ================= OSGi Packaging ==================
  class AkkaOSGiBundleProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with BNDPlugin {
    override def bndClasspath = compileClasspath
    override def bndPrivatePackage = Seq("")
    override def bndExportPackage = Seq("se.scalablesolutions.akka.*;version=0.9")
  }

  class AkkaOSGiDependenciesBundleProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with BNDPlugin {
    override def bndClasspath = compileClasspath
    override def bndPrivatePackage = Seq("")
    override def bndImportPackage = Seq("*;resolution:=optional")
    override def bndExportPackage = Seq(
      "org.aopalliance.*;version=1.0.0",

      // Provided by other bundles
      "!se.scalablesolutions.akka.*", 
      "!net.liftweb.*",
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
    val scala_bundle = "com.weiglewilczek.scala-lang-osgi" % "scala-library_2.8.0.RC3" % "1.0" % "compile" intransitive()

    // Lift bundles
    val lift_actor = "net.liftweb" % "lift-actor" % LIFT_VERSION % "compile" intransitive()
    val lift_common = "net.liftweb" % "lift-common" % LIFT_VERSION % "compile" intransitive()
    val lift_json = "net.liftweb" % "lift-json" % LIFT_VERSION % "compile" intransitive()
    val lift_util = "net.liftweb" % "lift-util" % LIFT_VERSION % "compile" intransitive()
    //val lift_webkit = "net.liftweb" % "lift-webkit" % LIFT_VERSION % "compile" intransitive()
    //val lift_testkit = "net.liftweb" % "lift-testkit" % LIFT_VERSION % "compile" intransitive()

    // Camel bundles
    val camel_core = "org.apache.camel" % "camel-core" % "2.3.0" % "compile" intransitive()
    val fusesource_commonman = "org.fusesource.commonman" % "commons-management" % "1.0" intransitive()

    // Spring bundles
    val spring_beans = "org.springframework" % "spring-beans" % "3.0.2.RELEASE" % "compile" intransitive()
    val spring_context = "org.springframework" % "spring-context" % "3.0.2.RELEASE" % "compile" intransitive()
    val spring_jms = "org.springframework" % "spring-jms" % "3.0.2.RELEASE" % "compile" intransitive()
    val spring_aop = "org.springframework" % "spring-aop" % "3.0.2.RELEASE" % "compile" intransitive()
    val spring_asm = "org.springframework" % "spring-asm" % "3.0.2.RELEASE" % "compile" intransitive()
    val spring_core = "org.springframework" % "spring-core" % "3.0.2.RELEASE" % "compile" intransitive()
    val spring_expression = "org.springframework" % "spring-expression" % "3.0.2.RELEASE" % "compile" intransitive()
    val spring_tx = "org.springframework" % "spring-tx" % "3.0.2.RELEASE" % "compile" intransitive()

    val commons_io = "commons-io" % "commons-io" % "1.4" % "compile" intransitive()
    val commons_pool = "commons-pool" % "commons-pool" % "1.5.4" % "compile" intransitive()
    val commons_codec = "commons-codec" % "commons-codec" % "1.4" % "compile" intransitive()
    val commons_fileupload = "commons-fileupload" % "commons-fileupload" % "1.2.1" % "compile" intransitive()
    val jackson_core = "org.codehaus.jackson" % "jackson-core-asl" % "1.2.1" % "compile" intransitive()
    val jackson = "org.codehaus.jackson" % "jackson-mapper-asl" % "1.2.1" % "compile" intransitive()
    val netty = "org.jboss.netty" % "netty" % "3.2.0.CR1" % "compile" intransitive()
    val guicey = "org.guiceyfruit" % "guice-all" % "2.0" % "compile" intransitive()
    val joda = "joda-time" % "joda-time" % "1.6" intransitive()

    val jta_1_1 = "org.apache.geronimo.specs" % "geronimo-jta_1.1_spec" % "1.1.1" % "compile" intransitive()
    val jms_1_1 = "org.apache.geronimo.specs" % "geronimo-jms_1.1_spec" % "1.1.1" % "compile" intransitive()
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1" % "compile" intransitive()

    override def packageAction = task {
      val libs: Seq[Path] = managedClasspath(config("compile")).get.toSeq
      val prjs: Seq[Path] = info.dependencies.toSeq.asInstanceOf[Seq[DefaultProject]].map(_.jarPath)
      val all = libs ++ prjs
      FileUtilities.copyFlat(all, outputPath / "bundles", log)
      None
    }
  }

  class AkkaOSGiParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val akka_osgi_bundle = project("akka-osgi-bundle", "akka-osgi-bundle",
      new AkkaOSGiBundleProject(_), akka_kernel)
    lazy val akka_osgi_dependencies_bundle = project("akka-osgi-dependencies-bundle", "akka-osgi-dependencies-bundle",
      new AkkaOSGiDependenciesBundleProject(_), akka_osgi_bundle)
    lazy val akka_osgi_assembly = project("akka-osgi-assembly", "akka-osgi-assembly",
      new AkkaOSGiAssemblyProject(_), akka_osgi_bundle, akka_osgi_dependencies_bundle)
  }

  // ================= TEST ==================
  class AkkaActiveObjectTestProject(info: ProjectInfo) extends DefaultProject(info) {
    // testing
    val junit = "junit" % "junit" % "4.5" % "test"
    val jmock = "org.jmock" % "jmock" % "2.4.0" % "test"
  }

  // ================= EXAMPLES ==================
  class AkkaSampleAntsProject(info: ProjectInfo) extends DefaultSpdeProject(info) {
    val scalaToolsSnapshots = ScalaToolsSnapshots
    override def spdeSourcePath = mainSourcePath / "spde"
  }

  class AkkaSampleChatProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)
  class AkkaSamplePubSubProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleLiftProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val commons_logging = "commons-logging" % "commons-logging" % "1.1.1" % "compile"
    val lift = "net.liftweb" % "lift-webkit" % LIFT_VERSION % "compile"
    val lift_util = "net.liftweb" % "lift-util" % LIFT_VERSION % "compile"
    val servlet = "javax.servlet" % "servlet-api" % "2.5" % "compile"
    // testing
    val jetty = "org.mortbay.jetty" % "jetty" % "6.1.22" % "test"
    val junit = "junit" % "junit" % "4.5" % "test"
  }

  class AkkaSampleRestJavaProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleRemoteProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath)

  class AkkaSampleRestScalaProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1.1" % "compile"
  }

  class AkkaSampleCamelProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val spring_jms = "org.springframework" % "spring-jms" % "3.0.1.RELEASE" % "compile"
    val camel_jetty = "org.apache.camel" % "camel-jetty" % "2.3.0" % "compile"
    val camel_jms = "org.apache.camel" % "camel-jms" % "2.3.0" % "compile"
    val activemq_core = "org.apache.activemq" % "activemq-core" % "5.3.2" % "compile"
  }

  class AkkaSampleSecurityProject(info: ProjectInfo) extends AkkaDefaultProject(info, deployPath) {
    val jsr311 = "javax.ws.rs" % "jsr311-api" % "1.1.1" % "compile"
    val jsr250 = "javax.annotation" % "jsr250-api" % "1.0" % "compile"
    val commons_codec = "commons-codec" % "commons-codec" % "1.3" % "compile"
  }

  class AkkaSampleOSGiProject(info: ProjectInfo) extends AkkaDefaultProject(info, distPath) with BNDPlugin {
    override def bndClasspath = compileClasspath

    val osgi_core = "org.osgi" % "org.osgi.core" % "4.2.0"

    override def bndBundleActivator = Some("sample.osgi.Activator")
    override def bndPrivatePackage = Nil
    override def bndExportPackage = Seq("sample.osgi.*;version=0.9")
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
    lazy val akka_sample_osgi = project("akka-sample-osgi", "akka-sample-osgi",
      new AkkaSampleOSGiProject(_), akka_core)
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
