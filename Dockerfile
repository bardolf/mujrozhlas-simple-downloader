FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache ffmpeg
COPY --from=build /app/build/libs/mrsd-all.jar /app/app.jar

ENV PORT=8080 \
    DOWNLOAD_DIR=/downloads \
    DELETED_DIR=/deleted \
    DB_PATH=/data/mrsd.db

EXPOSE 8080
VOLUME ["/data", "/downloads", "/deleted"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
