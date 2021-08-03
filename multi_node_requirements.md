ssh access to the local machine without password

this works:

sbt -Dakka.test.timefactor=1 \
 -Dakka.cluster.assert=on \
 -Dsbt.override.build.repos=false \
 -Dakka.test.multi-node=true \
 -Dakka.test.multi-node.targetDirName=${PWD}/target/${JOB_ID} \
 -Dakka.test.multi-node.java=/Users/andreatp/.sdkman/candidates/java/11.0.8.hs-adpt/bin/java \
 -Dmultinode.XX:MetaspaceSize=128M \
 -Dmultinode.XX:+UseCompressedOops \
 -Dmultinode.Xms256M \
 -Dmultinode.Xmx512M
