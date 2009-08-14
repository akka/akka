@ECHO OFF
 
@REM IF "%1"=="" goto error
IF "%AKKA_HOME%"=="" goto error_no_akka_home
IF "%JAVA_COMMAND%"=="" set JAVA_COMMAND=%JAVA_HOME%\bin\java
IF "%JAVA_HOME%"=="" goto error_no_java_home
 
set VERSION=0.5
set LIB_DIR=%AKKA_HOME%\lib

set CLASSPATH=%AKKA_HOME%\config
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\akka-kernel-0.5.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\akka-util-java-0.5.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\aopalliance-1.0.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\asm-3.1.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\aspectwerkz-jdk5-2.1.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\aspectwerkz-nodeps-jdk5-2.1.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\atmosphere-core-0.3.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\atmosphere-portable-runtime-0.3.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\camel-core-2.0-SNAPSHOT.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\atmosphere-compat-0.3.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\cassandra-0.4.0-trunk.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\cglib-2.2.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\commons-cli-1.1.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\commons-io-1.3.2.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\commons-logging-1.0.4.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\commons-math-1.1.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\commons-pool-1.5.1.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\configgy-1.3.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\fscontext.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\google-collect-snapshot-20090211.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\grizzly-comet-webserver-1.8.6.3.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\guice-core-2.0-SNAPSHOT.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\jackson-core-asl-1.1.0.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\jackson-mapper-asl-1.1.0.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\jersey-client-1.1.1-ea.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\jersey-core-1.1.1-ea.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\jersey-json-1.1.1-ea.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\jersey-server-1.1.1-ea.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\jersey-scala-1.1.2-ea-SNAPSHOT.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\JSAP-2.1.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\jsr311-api-1.0.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\libfb303.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\libthrift.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\log4j-1.2.15.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\netty-3.1.0.GA.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\providerutil.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\protobuf-java-2.1.0.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\scala-library-2.7.5.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\scala-stats-1.0.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\servlet-api-2.5.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\slf4j-api-1.4.3.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\slf4j-log4j12-1.4.3.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\stringtemplate-3.0.jar
set CLASSPATH=%CLASSPATH%;%LIB_DIR%\zookeeper-3.1.0.jar

@REM Add for debugging; -Xdebug -Xrunjdwp;transport=dt_socket,server=y,suspend=y,address=5005 \
@REM To have Akka dump the generated classes, add the '-Daspectwerkz.transform.dump=*' option and it will dump classes to $BASE_DIR/_dump

set JVM_OPTS=-server -Xms128M -Xmx1G -XX:SurvivorRatio=8 -XX:TargetSurvivorRatio=90 -XX:+AggressiveOpts -XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:CMSInitiatingOccupancyFraction=1 -XX:+CMSParallelRemarkEnabled -XX:+HeapDumpOnOutOfMemoryError -Dcom.sun.management.jmxremote.port=8080 -Dcom.sun.management.jmxremote.ssl=false -Djava.naming.factory.initial=com.sun.jndi.fscontext.RefFSContextFactory -Dcom.sun.grizzly.cometSupport=true -Dcom.sun.management.jmxremote.authenticate=false

@ECHO ON

%JAVA_HOME%\bin\java %JVM_OPTS% -cp %CLASSPATH% se.scalablesolutions.akka.kernel.Kernel %1 %2 %3
 
@exit /B %ERRORLEVEL%
  
;error
    IF EXIST "%AKKA_HOME%\bin\usage.txt" (
        type %AKKA_HOME%\bin\usage.txt"
    ) ELSE (
        echo AKKA_HOME does not point to the Akka directory
    )
@goto error_exit
 
;error_no_java_home
@echo Please specify the JAVA_HOME environment variable.
@goto error_exit
 
;error_no_akka_home
@echo Please specify the AKKA_HOME environment variable.
@goto error_exit
 
;error_exit
@exit /B -1