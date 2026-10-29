FROM maven:3.9.16-eclipse-temurin-17 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xmx384m -XX:MaxMetaspaceSize=192m"
COPY backend/pom.xml ./pom.xml
RUN mvn -B -ntp dependency:go-offline
COPY backend/src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
COPY --from=build --chown=10001:10001 /build/target/recondesk-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
