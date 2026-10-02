#!/bin/sh
# usage: run.sh mode n N [stack]
/usr/bin/time -l java -Xmx6g -Xss512k --add-exports java.base/jdk.internal.vm=ALL-UNNAMED -cp out Spike "$@" 2>&1 | egrep 'mode=|maximum resident|real|Exception|Error' | tr '\n' ' '; echo
