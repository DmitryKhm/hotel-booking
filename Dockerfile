FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package
ARG MODULE
RUN cp "${MODULE}/target/${MODULE}-1.0.0.jar" /application.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /application.jar /app/application.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
