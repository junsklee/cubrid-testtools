#!/bin/bash
echo "Mock build started"
sleep 1
echo "Mock build completed"
mkdir -p build_x86_64_debug
touch build_x86_64_debug/cubrid_debug.tar.gz
exit 0
