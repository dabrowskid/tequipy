# ----- Build stage: compile + bootJar -----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Pre-copy wrapper + Gradle config so the dependency layer is cached separately from sources.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon help > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*.jar /app/app.jar

# ----- Layered-jar extraction for better runtime caching -----
FROM eclipse-temurin:25-jdk AS extract
WORKDIR /extract
COPY --from=build /app/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher

# ----- Runtime stage -----
FROM eclipse-temurin:25-jdk
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --create-home --shell /bin/false app

COPY --from=extract --chown=app:app /extract/app/dependencies/         ./
COPY --from=extract --chown=app:app /extract/app/spring-boot-loader/   ./
COPY --from=extract --chown=app:app /extract/app/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /extract/app/application/          ./

USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
