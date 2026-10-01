# Этап 1: сборка jar внутри контейнера с Maven (локальные Java и Maven не нужны)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Сначала только pom.xml: слой с зависимостями кэшируется и не скачивается заново при изменении кода
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -Dmaven.test.skip=true

# Этап 2: в итоговом образе только JRE и jar, без Maven и исходников
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
