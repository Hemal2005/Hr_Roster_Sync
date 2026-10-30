# syntax=docker/dockerfile:1

# Build stage: a full JDK to compile and package with the Maven wrapper.
FROM eclipse-temurin:21.0.12_8-jdk-noble AS build
WORKDIR /workspace

# Dependencies first: this layer is reused until pom.xml or the wrapper changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline

COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -Dmaven.test.skip=true package \
 && java -Djarmode=tools -jar target/hr-roster-sync-*.jar extract --layers --launcher --destination target/extracted

# Run stage: only a JRE and the application, as an unprivileged user.
FROM eclipse-temurin:21.0.12_8-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app

# Least to most frequently changed, so a code change only replaces the last layer.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER app
EXPOSE 8080
# Size the heap from the container's memory limit, not the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
