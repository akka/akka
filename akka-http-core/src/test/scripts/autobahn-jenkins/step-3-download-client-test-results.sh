#!/usr/bin/env bash
# "download" [client] results from docker container

rm -rf localhost\:8080/
echo "Downloading client results report..."
wget --recursive --quiet --page-requisites --html-extension \
     --convert-links --domains localhost --no-parent \
     --directory-prefix=client/ \
     http://localhost:8080/cwd/reports/clients/index.html

rm -f client/index.json*
wget --quiet --directory-prefix=client/ \
     http://localhost:8080/cwd/reports/clients/index.json
