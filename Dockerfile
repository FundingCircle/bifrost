FROM quay.io/fundingcircle/alpine-java
MAINTAINER fundingcircle "engineering@fundingcircle.com" 

RUN apk add --update bash perl grep


ADD ./target/bifrost-*-standalone.jar  /opt/bifrost/lib/
ADD ./docker/config.edn.tmpl  /opt/bifrost/conf/config.edn.tmpl
ADD ./docker/logback.xml /opt/bifrost/conf/logback.xml
ADD ./docker/configure-and-start.sh  /

CMD envconsul-launch -prefix bifrost/config /configure-and-start.sh
