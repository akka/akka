#! /bin/bash

rm -f all-projects.txt
rm -f multi-node-projects.txt

sbt -no-colors --error printAggregatedProjects > all-projects.txt
touch multi-node-projects.txt

cat all-projects.txt | while read line 
do
  if sh -c "sbt -no-colors --error $line/multi-jvm:scalaSource" ; then
    echo "${line}" >> multi-node-projects.txt
  fi
done

yq e -i '.jobs.*.strategy.matrix.project = []' .github/workflows/multi-node.yml
cat multi-node-projects.txt | while read line 
do
  yq e -i ".jobs.*.strategy.matrix.project += \"$line\"" .github/workflows/multi-node.yml
done
