# For Unix: 
# mvn -o <target> |sed -e s/\\[WARNING\\][[:space:]]//g |grep -v "Finished at"

install:
		mvn -o scala:compile |sed -e 's/\[INFO\] //g' |sed -e 's/\[WARNING\] //g' |grep -v "Finished at" |grep -v "Total time"

