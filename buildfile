require 'buildr/scala'

#options.test = false

VERSION_NUMBER = '0.1'

ENV['AKKA_HOME'] ||= '.'

repositories.remote << 'http://www.ibiblio.org/maven2'
repositories.remote << 'http://scala-tools.org/repo-releases'
repositories.remote << 'http://scala-tools.org/repo-snapshots'
repositories.remote << 'http://www.lag.net/repo'

AKKA_KERNEL =     'se.scalablesolutions.akka:akka-kernel:jar:0.1' 
#AKKA_SUPERVISOR = 'se.scalablesolutions.akka:akka-supervisor:jar:0.1' 
AKKA_UTIL_JAVA =  'se.scalablesolutions.akka:akka-util-java:jar:0.1'
AKKA_API_JAVA =   'se.scalablesolutions.akka:akka-api-java:jar:0.1'

SCALA =        'org.scala-lang:scala-library:jar:2.7.3'
SCALATEST =    'org.scalatest:scalatest:jar:0.9.5'
GUICEYFRUIT = ['org.guiceyfruit:guice-core:jar:2.0-SNAPSHOT', 
               'org.guiceyfruit:guice-jsr250:jar:2.0-SNAPSHOT']
JERSEY =      ['com.sun.jersey:jersey-core:jar:1.0.1',
               'com.sun.jersey:jersey-server:jar:1.0.1',
               'com.sun.jersey:jersey-json:jar:1.0.1',
               'com.sun.jersey:jersey-atom:jar:1.0.1',
               'javax.ws.rs:jsr311-api:jar:1.0']
VOLDEMORT =   ['voldemort:voldemort:jar:0.4a',
               'voldemort:voldemort-contrib:jar:0.4a']
SLF4J =       ['org.slf4j:slf4j-log4j12:jar:1.4.3', 
               'org.slf4j:slf4j-api:jar:1.4.3',
               'log4j:log4j:jar:1.2.13']
CONFIGGY =     'net.lag:configgy:jar:1.2'
ZOOKEEPER =    'org.apache:zookeeper:jar:3.1.0'
GRIZZLY =      'com.sun.grizzly:grizzly-servlet-webserver:jar:1.8.6.3'
JUNIT4 =       'junit:junit:jar:4.5'
JUNIT3 =       'junit:junit:jar:3.8.2'
GOOGLE_COLLECT = 'com.google.code.google-collections:google-collect:jar:snapshot-20080530'
JDOM =         'jdom:jdom:jar:1.0'
MINA_CORE =    'com.assembla.scala.mina:mina-core:jar:2.0.0-M2-SNAPSHOT'
MINA_SCALA =   'com.assembla.scala.mina:mina-integration-scala:jar:2.0.0-M2-SNAPSHOT'

desc 'The Akka Actor Kernel'
define 'akka' do
  project.version = VERSION_NUMBER
  project.group = 'se.scalablesolutions.akka' 
  manifest['Copyright'] = 'Scalable Solutions (C) 2009'
  compile.options.target = '1.5'

  desc 'Akka Java Utilities (annotations)'
  define 'util-java' do
    compile
    package :jar
  end
  
  #desc 'Implementation of Erlangs Supervisor and GenericServer behaviors'
  #define 'supervisor' do
  #  compile.with(CONFIGGY)
  #  test.using :scalatest
  # package :jar
  #end
  
  desc 'Akka Actor kernel core implementation'
  define 'kernel' do
    compile.with(AKKA_UTIL_JAVA, GUICEYFRUIT, MINA_CORE, MINA_SCALA, JERSEY, VOLDEMORT, ZOOKEEPER, SLF4J, GRIZZLY, CONFIGGY, JUNIT3, SCALATEST)
    test.using :scalatest
    package :jar
  end

  #desc 'Akka DB'
  #define 'db' do
  #  compile.with(AKKA_KERNEL, MINA_CORE, MINA_SCALA, ZOOKEEPER, CONFIGGY, SLF4J, JUNIT3)
  #  test.using :scalatest
  #  package :jar
  #end
  
  desc 'Akka Java API'
  define 'api-java' do
    compile.with(AKKA_KERNEL, AKKA_UTIL_JAVA, GUICEYFRUIT, JUNIT4)
    package :jar
  end

  package(:zip).include 'README'
  package(:zip).include 'bin/*', :path=>'bin'
  package(:zip).include 'config/*', :path=>'config'
  package(:zip).include 'kernel/lib/*', :path=>'lib'
  package(:zip).include 'kernel/target/*.jar', :path=>'lib'
  #package(:zip).include 'supervisor/target/*.jar', :path=>'lib'
  package(:zip).include 'api-java/target/*.jar', :path=>'lib'
  package(:zip).include 'util-java/target/*.jar', :path=>'lib'

  task :run => [:package] do |t|
    puts "-------------------------"
    puts "Running Akka Actor Kernel"
    puts "-------------------------"
    puts "\n"

#    uri = URI("file://./lib")
#    uri.upload file('kernel')

    cp = [SCALA, GUICEYFRUIT, JERSEY, VOLDEMORT, GOOGLE_COLLECT, JDOM, ZOOKEEPER, SLF4J, GRIZZLY, CONFIGGY, project('kernel').package(:jar)]
#    Java.java('se.scalablesolutions.akka.kernel.Kernel', {:classpath => '-cp ' + cp})
#    cp = FileList[_('lib/*')].join(File::PATH_SEPARATOR)
    puts "Running with classpath:\n" + cp
    Java.java('se.scalablesolutions.akka.Boot', 'se.scalablesolutions.akka.kernel.Kernel', {:classpath => cp})
  end

end

