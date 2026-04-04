FROM amazoncorretto:21-alpine AS build
WORKDIR /app
COPY . .
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew shadowJar --no-daemon

FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=build /app/build/libs/app.jar app.jar
EXPOSE 8090
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
