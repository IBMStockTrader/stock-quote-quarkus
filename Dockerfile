## Stage 1 : build with maven builder image with native capabilities
FROM quay.io/quarkus/centos-quarkus-maven:20.2.0-java11 AS build
COPY pom.xml /usr/src/app/
COPY src/main/resources/mycerts3 /tmp/mycerts3
RUN mvn -f /usr/src/app/pom.xml -B de.qaware.maven:go-offline-maven-plugin:1.2.5:resolve-dependencies
COPY src /usr/src/app/src
USER root
RUN chown -R quarkus /usr/src/app \
    && chmod "a+r" /tmp/mycerts3
USER quarkus
RUN mvn -f /usr/src/app/pom.xml -Pnative clean package

## Stage 2 : create the docker final image
FROM registry.access.redhat.com/ubi8/ubi-minimal
WORKDIR /work/
COPY --from=build /usr/src/app/target/*-runner /work/application

# set up permissions for user `1001`
USER root
RUN chmod 775 /work /work/application \
  && chown -R 1001 /work \
  && chmod -R "g+rwX" /work \
  && chown -R 1001:root /work
EXPOSE 8080
USER 1001
# resetting base image entrypoint with ENTRYPOINT [] doesnt work , so overriding here instead of using CMD
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
