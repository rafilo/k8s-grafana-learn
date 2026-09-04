FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src/ProductionSimulator.java src/ProductionSimulator.java
RUN javac -d out src/ProductionSimulator.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out /app/out
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-cp", "/app/out", "ProductionSimulator"]
