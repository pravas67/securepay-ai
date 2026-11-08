# --- Build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies first, so they're cached unless pom.xml changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests run in CI (they need Docker for Testcontainers), not inside the image build.
RUN mvn -q -B package -DskipTests

# --- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
# Run as an unprivileged user, never root.
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
COPY --from=build /src/target/securepay-backend-*.jar app.jar
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
