ARG VERSION=latest
FROM nexus.frankframework.org/frank-framework:${VERSION}

# Copy resources
COPY --chown=tomcat target/resources /opt/frank/resources

# Copy context, normally this file is mounted from the environment
# The example webapp uses an H2 internal database as it does not need to store actual data, so the context.xml can be part of the image
COPY --chown=tomcat target/context.xml /usr/local/tomcat/conf/Catalina/localhost/ROOT.xml
