#!/bin/bash

###  ENVIRONMENT VARIABLES
# REQUIRED:
#
#   KAFKA_ZOOKEEPER_CONNECT
#   S3_BUCKET_NAME
#   AWS_ACCESS_KEY_ID
#   AWS_SECRET_ACCESS_KEY
#
# OPTIONAL:
#
#   RIEMANN_HOST
#   TOPIC_WHITELIST
#   TOPIC_BLACKLIST

# setting defaults
export RIEMANN_HOST=${RIEMANN_HOST:-nil}
export TOPIC_WHITELIST=${TOPIC_WHITELIST:-nil}
export TOPIC_BLACKLIST=${TOPIC_BLACKLIST:-nil}


export CONFIG_FILE=/opt/bifrost/conf/config.edn
# replace variables in template with environment values
echo "TEMPLATE: generating configuation."
perl -pe 's/%%([A-Za-z0-9_]+)%%/defined $ENV{$1} ? $ENV{$1} : $&/eg' < ${CONFIG_FILE}.tmpl > $CONFIG_FILE

# check if all properties have been replaced
if grep -qoP '%%[^%]+%%' $CONFIG_FILE ; then
    echo "ERROR: Not all variable have been resolved,"
    echo "       please set the following variables in your environment:"
    grep -oP '%%[^%]+%%' $CONFIG_FILE | sed 's/%//g' | sort -u
    exit 1
fi

### NOTE : Using exec because if a monitor (supervisord or envconsul) tries to kill this script
###        the signal won't be passed onto the java process(which runs in a subshell).
###        By using exec, we avoid the java process running in a subshell.
exec java -Dlogback.configurationFile=/opt/bifrost/conf/logback.xml -server -jar /opt/bifrost/lib/bifrost-*-standalone.jar --config /opt/bifrost/conf/config.edn 
