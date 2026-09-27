# The server as a container, for runs where it sits on the same Docker network as Redis, Kafka and
# Postgres (no host port forwarding between the server and its dependencies).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/server-0.1.0.jar /app/server.jar
EXPOSE 28080 29100
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms1536m", "-Xmx1536m", "-jar", "/app/server.jar"]
