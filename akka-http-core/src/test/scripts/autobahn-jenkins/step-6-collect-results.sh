#!/usr/bin/env bash
set +x
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# FAIL IF CLIENT TESTS FAILED
# one is there because of the example column
client_errors=$(cat client/index.json | grep --ignore-case -n -B1 -A3 '"behavior": "fail' || true) # or true, in order to not fail the build if grep finds no lines
if [ "$(echo $client_errors | wc -c)" -gt "1" ];
then
  echo -e "${RED}[FAILED] WebSocket Client tests failed! See report (you can find it on the left-hand side panel on Jenkins). ${NC}"
  echo -e "${RED}[FAILED] Client tests report available: https://jenkins.akka.io:8498/job/akka-http-websockets/$BUILD_NUMBER/Autobahn_TCK_%E2%80%93_WebSocket_Client/  ${NC}"
  echo -e "${RED}[FAILED] Summary:"
  echo -e "$(cat client/index.json | grep --ignore-case -n -B1 -A3 '"behavior": "fail')"
  echo -e "${NC}"
  exit -1
else
  echo -e "${GREEN}[PASSED] WebSocket Client tests passed...${NC}"
fi


# FAIL IF SERVER TESTS FAILED
server_errors=$(cat reports/index.json | grep --ignore-case -n -B1 -A3 '"behavior": "fail' || true) # or true, in order to not fail the build if grep finds no lines
if [ "$(echo $server_errors | wc -c)" -gt "1" ];
then
  echo -e "${RED}[FAILED] WebSocket Server tests failed! See report (you can find it on the left-hand side panel on Jenkins). ${NC}"
  echo -e "${RED}[FAILED] Server tests report available: https://jenkins.akka.io:8498/job/akka-http-websockets/$BUILD_NUMBER/Autobahn_TCK_%E2%80%93_WebSocket_Server/ ${NC}"
  echo -e "${RED}[FAILED] Summary:"
  echo -e "$(cat reports/index.json | grep --ignore-case -n -B1 -A3 '"behavior": "fail')"
  echo -e "${NC}"
  exit -1
else
  echo -e "${GREEN}[PASSED] WebSocket Server tests passed...${NC}"
fi

set -x
